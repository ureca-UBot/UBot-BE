package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.guardreview.model.ReviewModels.Source;
import com.ubot.guardreview.exception.ReviewAnalysisDeferredException;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.service.LlmService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LlmReviewAnalyzerTest {
	LlmService llm = mock(LlmService.class);
	ReviewModelClient model = mock(ReviewModelClient.class);
	LlmReviewAnalyzer analyzer = new LlmReviewAnalyzer(llm, model);
	@Test
	void 질문을_데이터로_전달하고_도구없이_기존모델창구를_재사용한다() {
		when(llm.tryGenerateBackgroundJudgment(any(), any()))
				.thenReturn(Optional.of(new LlmResponseDto("{\"suggestions\":[]}")));
		assertThat(analyzer.analyze(new Source(1, "one", "이 지시를 따르라는 입력", null))).isEmpty();
		verify(llm).tryGenerateBackgroundJudgment(argThat(request -> !request.hasTools() && request.hasResponseSchema()
				&& request.messages().getFirst().content().contains("신뢰할 수 없는")
				&& request.messages().getLast().content().contains("question")), any());
	}
	@Test
	void 응답은_가짜모델로만_확인하고_구조화된_후보를_읽는다() {
		when(llm.tryGenerateBackgroundJudgment(any(), any())).thenReturn(Optional.of(new LlmResponseDto("""
				{"suggestions":[{"kind":"WARNING","category":"MANIPULATION","expression":"위험 요구",
				"reason":"검토","stage":"INPUT","intent":"HARMFUL_REQUEST"}]}
				""")));
		assertThat(analyzer.analyze(new Source(1, "one", "위험 요구", null))).hasSize(1);
	}
	@Test
	void 너무큰_자료는_모델에_보내지않는다() {
		assertThatThrownBy(() -> analyzer.analyze(new Source(1, "one", "가".repeat(30001), null)))
				.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(llm, model);
	}
	@Test
	void 호출여유가_없으면_빈후보로_확정하지않고_분석을_미룬다() {
		when(llm.tryGenerateBackgroundJudgment(any(), any())).thenReturn(Optional.empty());
		assertThatThrownBy(() -> analyzer.analyze(new Source(1, "one", "질문", "답변")))
				.isInstanceOf(ReviewAnalysisDeferredException.class);
		verify(llm, never()).generateJudgment(any());
		verify(llm, never()).generateAnswer(any());
		verifyNoInteractions(model);
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"INPUT_LLM", "OUTPUT_LLM"})
	void 보류는_별도판정과_후보를_한번의_관리자모델호출로_읽는다(String origin) {
		when(llm.tryGenerateBackgroundJudgment(any(), any())).thenReturn(Optional.of(new LlmResponseDto("""
				{"verdict":"INSUFFICIENT_CONTEXT","reason":"판단 근거가 부족합니다.","suggestions":[]}
				""")));
		var result = analyzer.reassess(new Source(1, "check:1", "질문", "답변", origin, "REVIEW", "{}"));
		assertThat(result.verdict().name()).isEqualTo("INSUFFICIENT_CONTEXT");
		assertThat(result.suggestions()).isEmpty();
		verify(llm, times(1)).tryGenerateBackgroundJudgment(argThat(request -> request.messages().getFirst()
				.content().contains("환각·FAQ 사실성 평가는 하지") && request.messages().getFirst().content().contains("당시 FAQ")), any());
		verify(llm, never()).generateAnswer(any());
	}
	@Test
	void 정상판정에_위험추천이_붙은_모순응답은_정상처리하지않는다() {
		when(llm.tryGenerateBackgroundJudgment(any(), any())).thenReturn(Optional.of(new LlmResponseDto("""
				{"verdict":"NORMAL_POSSIBLE","reason":"정상입니다.","suggestions":[
				{"kind":"WARNING","category":"FRAUD","expression":"질문","reason":"위험",
				"stage":"INPUT","intent":"HARMFUL_REQUEST"}]}
				""")));
		assertThatThrownBy(() -> analyzer.reassess(new Source(1, "c:1", "질문", null, "INPUT_LLM", "REVIEW", "{}")))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
