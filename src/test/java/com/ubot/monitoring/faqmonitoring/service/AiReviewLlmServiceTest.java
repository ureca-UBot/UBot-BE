package com.ubot.monitoring.faqmonitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.service.LlmService;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateReviewResult;
import com.ubot.monitoring.faqmonitoring.format.DuplicateSuspectedReviewFormat;
import com.ubot.monitoring.faqmonitoring.format.LowRagQualityReviewFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AiReviewLlmServiceTest {

	@Mock private LlmService llmService;

	private AiReviewLlmService service;

	@BeforeEach
	void setUp() {
		service = new AiReviewLlmService(llmService);
		ReflectionTestUtils.setField(service, "maxLlmQueueWaitMillis", 1_500L);
	}

	@Test
	@DisplayName("중복 검토 요청에 전용 JSON 스키마와 LLM 대기 시간을 적용한다")
	void 중복_검토는_중복_스키마와_대기_시간을_사용하고_응답을_파싱한다() {
		when(llmService.generateJudgment(any(LlmRequestDto.class), eq(1_500L)))
				.thenReturn(new LlmResponseDto("""
						{"candidateAssessments":[
						  {"candidateFaqId":10,"result":"DUPLICATE","reason":"답변 범위가 같습니다."}
						]}
						"""));

		var result = service.generateDuplicateSuspectedReview("검토 자료", List.of(10L));

		assertThat(result.candidateAssessments().getFirst().result())
				.isEqualTo(DuplicateReviewResult.DUPLICATE);
		LlmRequestDto request = capturedRequest();
		assertRequest(request, "검토 자료", DuplicateSuspectedReviewFormat.schema());
	}

	@Test
	@DisplayName("대표 질문 검토 요청에 전용 JSON 스키마와 LLM 대기 시간을 적용한다")
	void 대표_질문_검토는_대표_질문_스키마를_사용하고_응답을_파싱한다() {
		when(llmService.generateJudgment(any(LlmRequestDto.class), eq(1_500L)))
				.thenReturn(new LlmResponseDto("""
						{"needsUpdate":true,"reason":"답변 범위를 드러내지 못합니다.",
						 "suggestedQuestion":"현재 포인트 잔액은 얼마인가요?"}
						"""));

		var result = service.generateLowRagQualityRepresentativeAssessment("대표 질문 자료");

		assertThat(result.needsUpdate()).isTrue();
		assertThat(result.suggestedQuestion()).isEqualTo("현재 포인트 잔액은 얼마인가요?");
		LlmRequestDto request = capturedRequest();
		assertRequest(request, "대표 질문 자료", LowRagQualityReviewFormat.representativeSchema());
	}

	@Test
	@DisplayName("변형 질문 검토 요청에 전용 JSON 스키마를 적용하고 모든 판정을 파싱한다")
	void 변형_질문_검토는_변형_질문_스키마를_사용하고_모든_질문을_파싱한다() {
		when(llmService.generateJudgment(any(LlmRequestDto.class), eq(1_500L)))
				.thenReturn(new LlmResponseDto("""
						{"questionAssessments":[
						  {"questionNumber":1,"answerableByCurrentFaq":true},
						  {"questionNumber":2,"answerableByCurrentFaq":false}
						]}
						"""));

		var result = service.generateLowRagQualityVariantAssessment("변형 질문 자료", 2);

		assertThat(result.questionAssessments()).hasSize(2);
		LlmRequestDto request = capturedRequest();
		assertRequest(request, "변형 질문 자료", LowRagQualityReviewFormat.variantSchema());
	}

	private LlmRequestDto capturedRequest() {
		ArgumentCaptor<LlmRequestDto> captor = ArgumentCaptor.forClass(LlmRequestDto.class);
		verify(llmService).generateJudgment(captor.capture(), eq(1_500L));
		return captor.getValue();
	}

	private void assertRequest(
			LlmRequestDto request,
			String expectedUserPrompt,
			java.util.Map<String, Object> expectedSchema
	) {
		assertThat(request.messages()).hasSize(2);
		assertThat(request.messages().getFirst().role()).isEqualTo(LlmMessageRole.SYSTEM);
		assertThat(request.messages().getFirst().content()).isNotBlank();
		assertThat(request.messages().getLast().role()).isEqualTo(LlmMessageRole.USER);
		assertThat(request.messages().getLast().content()).isEqualTo(expectedUserPrompt);
		assertThat(request.responseSchema()).isEqualTo(expectedSchema);
	}
}
