package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ubot.guardreview.model.ReviewCheckModels.*;
import static com.ubot.guardreview.model.ReviewModels.*;

import com.ubot.ai.config.AiInputGuardProperties;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.service.*;
import com.ubot.chat.service.ChatInputGuardService;
import com.ubot.chat.service.ChatPromptInjectionService;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.guardreview.repository.ReviewCheckRepository;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.service.AiGuardPromptService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** 실제 가드 파서·프롬프트와 기록 서비스를 연결하되, 모델과 DB 전송은 가짜입니다. */
class ReviewCheckConnectionTest {
	ReviewCheckRepository repository = mock(ReviewCheckRepository.class);
	ReviewCheckService checks = new ReviewCheckService(repository, mock(PlatformTransactionManager.class));
	AiGuardModelService model = mock(AiGuardModelService.class);
	ForbiddenWordFilterService words = mock(ForbiddenWordFilterService.class);
	AiGuardPromptService prompts = new AiGuardPromptService(words);
	AiLlmOutputGuardService output = new AiLlmOutputGuardService(model, prompts, checks);
	AnswerMaterials materials = AnswerMaterials.builder("요금 안내")
			.addSection(new ContextSection("공식 근거", "요금은 월 5000원입니다.")).build().withReviewAttempt(1L);

	@Test
	void 실제_ALLOW만_PASS이며_판정당_모델은_한번만_호출한다() {
		when(model.generate(any())).thenReturn(new LlmResponseDto("{\"findings\":[]}"));
		output.validate("요금은 월 5000원입니다.", List.of("faq:1"), materials, null);
		verify(model, times(1)).generate(any());
		verify(repository).save(any(UUID.class), eq(1L), eq(Origin.OUTPUT_LLM), eq(Outcome.PASS), eq(List.of()),
				eq("요금 안내"), eq("요금은 월 5000원입니다."), eq("요금은 월 5000원입니다."), anyString(), anyString());
	}

	@Test
	void 차단_원문과_실제제공근거를_저장하되_추가분석호출은_없다() {
		when(model.generate(any())).thenReturn(new LlmResponseDto("""
				{"findings":[{"certainty":"CONFIRMED","type":"POLICY_CONTRADICTION","answer_quote":"무료입니다"}]}
				"""));
		assertThatThrownBy(() -> output.validate("무료입니다", List.of("faq:1"), materials, null))
				.isInstanceOf(LlmException.class);
		var snapshot = ArgumentCaptor.forClass(String.class);
		verify(repository).save(any(), eq(1L), eq(Origin.OUTPUT_LLM), eq(Outcome.REJECTED),
				eq(List.of("POLICY_CONTRADICTION")), eq("요금 안내"), eq("무료입니다"), isNull(), anyString(), snapshot.capture());
		var context = JsonMapper.builder().build().readTree(snapshot.getValue());
		assertThat(context.path("additional_evidence").get(0).path("content").asString()).contains("5000원");
		assertThat(context.path("evidence_ids").get(0).asString()).isEqualTo("faq:1");
		verify(model, times(1)).generate(any());
	}

	@Test
	void 형식오류는_ERROR이며_차단이나_통과로_기록하지않는다() {
		when(model.generate(any())).thenReturn(new LlmResponseDto("잘린 JSON"));
		assertThatThrownBy(() -> output.validate("정상 답변", List.of(), materials, null)).isInstanceOf(LlmException.class);
		verify(repository).save(any(), eq(1L), eq(Origin.OUTPUT_LLM), eq(Outcome.ERROR), anyList(),
				anyString(), eq("정상 답변"), isNull(), isNull(), anyString());
	}

	@Test
	void 저장장애가_기존_판정이나_재시도를_바꾸지않는다() {
		doThrow(new IllegalStateException("synthetic storage failure")).when(repository).save(any(), any(), any(), any(),
				any(), any(), any(), any(), any(), any());
		when(model.generate(any())).thenReturn(new LlmResponseDto("{\"findings\":[]}"));
		assertThatCode(() -> output.validate("정상 답변", List.of(), materials, null)).doesNotThrowAnyException();
		verify(model, times(1)).generate(any());
	}

	@Test
	void 저장실패로_기록이_없어도_실행한검사를_생략으로_바꾸지않는다() {
		checks.beginAttempt(1L);
		doThrow(new IllegalStateException("synthetic storage failure")).when(repository).save(any(), any(), any(), any(),
				any(), any(), any(), any(), any(), any());
		when(model.generate(any())).thenReturn(new LlmResponseDto("{\"findings\":[]}"));
		output.validate("정상 답변", List.of(), materials, null);
		checks.finishAttempt(1L, "요금 안내");
		verify(repository, never()).saveSkippedIfAbsent(1L, "요금 안내", Origin.OUTPUT_LLM);
		verify(repository).saveSkippedIfAbsent(1L, "요금 안내", Origin.INPUT_LLM);
	}

	@Test
	void 입력코드차단은_답변이나_attempt가_없어도_남는다() {
		var injection = mock(ChatPromptInjectionService.class);
		doThrow(new com.ubot.chat.exception.ChatException(com.ubot.chat.exception.ChatErrorCode.PROMPT_INJECTION_DETECTED))
				.when(injection).validatePromptInjection(anyString());
		var code = new ChatInputGuardService(words, injection, new AiInputGuardProperties(), checks);
		assertThatThrownBy(() -> code.prepareSubmittedQuestion("위험 요청을 수행해 주세요")).isInstanceOf(com.ubot.chat.exception.ChatException.class);
		verify(repository).save(any(), isNull(), eq(Origin.INPUT_CODE), eq(Outcome.REJECTED), anyList(),
				eq("위험 요청을 수행해 주세요"), isNull(), isNull(), isNull(), eq("{}"));
		verifyNoInteractions(model);
	}

	@Test
	void 입력통합_LLM은_기존호출한번에서_차단을_기록한다() {
		when(model.generate(any())).thenReturn(new LlmResponseDto("{\"decision\":\"BLOCK\",\"reason\":\"HARM\",\"sub_questions\":[]}"));
		var code = new ChatInputGuardService(words, new ChatPromptInjectionService(), new AiInputGuardProperties(), checks);
		var input = new AiInputGuardService(model, prompts, code, new AiInputGuardProperties(),
				mock(com.ubot.prompt.service.SubQuestionPromptService.class), mock(com.ubot.llm.service.LlmService.class), checks);
		assertThatThrownBy(() -> input.inspect(1L, "위험한 요구", "위험한 요구", List.of())).isInstanceOf(com.ubot.chat.exception.ChatException.class);
		verify(repository).save(any(), eq(1L), eq(Origin.INPUT_LLM), eq(Outcome.REJECTED), eq(List.of("HARM")),
				eq("위험한 요구"), isNull(), isNull(), isNull(), anyString());
		verify(model, times(1)).generate(any());
	}

	@Test
	void 현재_질문정리_연결에서도_실제_입력판정과_시도_ID를_기록한다() {
		when(model.generate(any())).thenReturn(new LlmResponseDto(
				"{\"decision\":\"ALLOW\",\"reason\":\"SAFE\",\"sub_questions\":[\"로밍 신청 방법\"]}"));
		var code = new ChatInputGuardService(words, new ChatPromptInjectionService(), new AiInputGuardProperties(), checks);
		var input = new AiInputGuardService(model, prompts, code, new AiInputGuardProperties(),
				mock(com.ubot.prompt.service.SubQuestionPromptService.class), mock(com.ubot.llm.service.LlmService.class), checks);
		var result = input.inspectForPlanning(1L, "로밍 신청 방법", "로밍 신청 방법", List.of());
		assertThat(result.planningFailed()).isFalse();
		assertThat(result.subQuestions()).containsExactly("로밍 신청 방법");
		verify(repository).save(any(), eq(1L), eq(Origin.INPUT_LLM), eq(Outcome.PASS), eq(List.of("SAFE")),
				eq("로밍 신청 방법"), isNull(), isNull(), isNull(), anyString());
		verify(model, times(1)).generate(any());
	}

	@Test
	void 안전판정_OFF의_분류기_실패복구는_실제_ALLOW로_기록하지않는다() {
		var properties = new AiInputGuardProperties();
		properties.setLlmEnabled(false);
		var planning = mock(com.ubot.llm.service.LlmService.class);
		when(planning.generateJudgment(any())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
		var code = new ChatInputGuardService(words, new ChatPromptInjectionService(), properties, checks);
		var input = new AiInputGuardService(model, prompts, code, properties,
				mock(com.ubot.prompt.service.SubQuestionPromptService.class), planning, checks);
		var result = input.inspectForPlanning(1L, "로밍 신청 방법", "로밍 신청 방법", List.of());
		assertThat(result.planningFailed()).isTrue();
		assertThat(result.subQuestions()).containsExactly("로밍 신청 방법");
		verify(repository).save(any(), eq(1L), eq(Origin.INPUT_LLM), eq(Outcome.SKIPPED), eq(List.of("DISABLED")),
				eq("로밍 신청 방법"), isNull(), isNull(), isNull(), anyString());
		verifyNoInteractions(model);
	}

	@Test
	void 불확실한_판정은_차단이아닌_REVIEW로_남는다() {
		when(model.generate(any())).thenReturn(new LlmResponseDto("""
				{"findings":[{"certainty":"UNCERTAIN","type":"INSUFFICIENT_EVIDENCE","answer_quote":"추가 확인이 필요합니다"}]}
				"""));
		assertThatThrownBy(() -> output.validate("추가 확인이 필요합니다", List.of(), materials, null)).isInstanceOf(LlmException.class);
		verify(repository).save(any(), eq(1L), eq(Origin.OUTPUT_LLM), eq(Outcome.REVIEW), anyList(),
				anyString(), eq("추가 확인이 필요합니다"), isNull(), anyString(), anyString());
	}

	@Test
	void 환각과_사실문제는_문장경고로만_허용한다() {
		var source = new Source(1, "check:1", "요금 안내", "무료입니다", "OUTPUT_LLM", "REJECTED", "{\"provided_faqs\":[{\"answer\":\"5000원\"}]}");
		var warning = new Suggestion(Kind.WARNING, "POLICY_CONTRADICTION", "무료입니다", "근거는 5000원입니다", Stage.OUTPUT, "FACTUAL_ERROR");
		assertThatCode(() -> ReviewService.validateSuggestion(source, warning)).doesNotThrowAnyException();
		assertThatThrownBy(() -> ReviewService.validateSuggestion(source, new Suggestion(Kind.WORD,
				warning.category(), "무료", warning.reason(), Stage.OUTPUT, "FACTUAL_ERROR"))).isInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
		var input = new Source(2, "check:2", "무료입니다", null, "INPUT_LLM", "REJECTED", "{}");
		assertThatThrownBy(() -> ReviewService.validateSuggestion(input, warning)).isInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
	}

	@Test
	void 입력문맥의_사용자문장은_검토하되_답변을_입력후보로_등록하지않는다() {
		var source = new Source(1, "input", "현재 질문", null, "INPUT_LLM", "REJECTED",
				"{\"history\":[{\"question\":\"위험 요구\",\"answer\":\"답변 표현\"}]}");
		assertThatCode(() -> ReviewService.validateSuggestion(source, new Suggestion(Kind.WARNING, "MANIPULATION",
				"위험 요구", "문맥 검토", Stage.INPUT, "HARMFUL_REQUEST"))).doesNotThrowAnyException();
		assertThatThrownBy(() -> ReviewService.validateSuggestion(source, new Suggestion(Kind.WARNING, "MANIPULATION",
				"답변 표현", "문맥 검토", Stage.INPUT, "HARMFUL_REQUEST"))).isInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
	}
}
