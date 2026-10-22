package com.ubot.monitoring.faqmonitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.common.GlobalException;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateCandidateAssessment;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateReviewResult;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateSuspectedAssessment;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqDuplicateEvidence;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqLowRagQualityQuestion;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.LowRagQualityQuestionAssessment;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.LowRagQualityRepresentativeAssessment;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.LowRagQualityVariantAssessment;
import com.ubot.monitoring.faqmonitoring.dto.request.DuplicateSuspectedReviewRequest;
import com.ubot.monitoring.faqmonitoring.repository.FaqMonitoringImprovementQueryRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AiReviewServiceTest {

	@Mock private FaqRepository faqRepository;
	@Mock private FaqMonitoringImprovementQueryRepository improvementQueryRepository;
	@Mock private AiReviewLlmService aiReviewLlmService;

	private AiReviewService service;

	@BeforeEach
	void setUp() {
		service = new AiReviewService(faqRepository, improvementQueryRepository, aiReviewLlmService);
		ReflectionTestUtils.setField(service, "lowGapThreshold", 0.07);
		ReflectionTestUtils.setField(service, "improvementTopK", 3);
		ReflectionTestUtils.setField(service, "maxDuplicateCandidates", 5);
		ReflectionTestUtils.setField(service, "maxQuestionsPerDuplicateCandidate", 20);
		ReflectionTestUtils.setField(service, "maxDisplayedQuestionsPerDuplicateCandidate", 5);
		ReflectionTestUtils.setField(service, "maxLowRagQualityQuestions", 20);
		ReflectionTestUtils.setField(service, "maxDisplayedQuestionsForLowRagQuality", 5);
	}

	@Test
	@DisplayName("중복 판정 결과를 후보별 Markdown 권고와 세부사항으로 조립한다")
	void 중복_판정을_후보별_Markdown_권고와_세부사항으로_조립한다() {
		Faq target = faq(1L, "유심을 재발급받으려면 어떻게 하나요?", "매장이나 앱에서 신청할 수 있습니다.");
		Faq duplicate = faq(10L, "매장에서 유심을 재발급받을 수 있나요?", "매장에서 재발급할 수 있습니다.");
		Faq separate = faq(20L, "유심 배송은 얼마나 걸리나요?", "배송에는 이틀이 걸립니다.");
		List<String> duplicateQuestions = List.of("질문 1", "질문 2", "질문 3", "질문 4", "질문 5", "질문 6");
		when(faqRepository.findActiveById(1L)).thenReturn(Optional.of(target));
		when(faqRepository.findAllById(List.of(10L, 20L))).thenReturn(List.of(duplicate, separate));
		when(improvementQueryRepository.findDuplicateEvidence(
				eq(1L), eq(List.of(10L, 20L)), org.mockito.ArgumentMatchers.any(), eq(0.07), eq(20)))
				.thenReturn(List.of(
						new FaqDuplicateEvidence(10L, 7, duplicateQuestions),
						new FaqDuplicateEvidence(20L, 2, List.of("배송 질문"))));
		when(aiReviewLlmService.generateDuplicateSuspectedReview(anyString(), eq(List.of(10L, 20L))))
				.thenReturn(new DuplicateSuspectedAssessment(List.of(
						new DuplicateCandidateAssessment(20L, DuplicateReviewResult.NOT_DUPLICATE, "배송 문의는 별개입니다."),
						new DuplicateCandidateAssessment(10L, DuplicateReviewResult.DUPLICATE, "재발급 범위가 같습니다.")
				)));

		var response = service.reviewDuplicateSuspected(
				1L, new DuplicateSuspectedReviewRequest(List.of(10L, 20L)));

		assertThat(response.recommendation())
				.contains("- **FAQ 10**: 중복 판정입니다.")
				.contains("- **FAQ 20**: 별개의 FAQ 판정입니다. 조치가 필요하지 않습니다.");
		assertThat(response.details())
				.contains("### 후보 1: FAQ 10")
				.contains("- **함께 검색된 횟수**: 7")
				.contains("- **질문 5**: 질문 5")
				.doesNotContain("질문 6")
				.contains("#### 조치 권고\n- FAQ 1와 중복입니다.")
				.doesNotContain("대상 FAQ 1");
	}

	@Test
	@DisplayName("활성 상태인 중복 후보가 하나도 없으면 후보 없음 오류를 반환한다")
	void 활성_중복_후보가_하나도_없으면_후보_없음_오류를_반환한다() {
		when(faqRepository.findActiveById(1L)).thenReturn(Optional.of(faq(1L, "대상", "답변")));
		when(faqRepository.findAllById(List.of(10L))).thenReturn(List.of());

		assertThatThrownBy(() -> service.reviewDuplicateSuspected(
				1L, new DuplicateSuspectedReviewRequest(List.of(10L))))
				.isInstanceOfSatisfying(FaqException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(FaqErrorCode.FAQ_REVIEW_CANDIDATE_NOT_FOUND));
		verifyNoInteractions(aiReviewLlmService);
	}

	@Test
	@DisplayName("함께 검색된 질문 근거가 없는 중복 후보는 LLM을 호출하지 않는다")
	void 함께_검색된_질문_근거가_없는_후보는_LLM을_호출하지_않는다() {
		Faq target = faq(1L, "대상", "답변");
		Faq candidate = faq(10L, "후보", "후보 답변");
		when(faqRepository.findActiveById(1L)).thenReturn(Optional.of(target));
		when(faqRepository.findAllById(List.of(10L))).thenReturn(List.of(candidate));
		when(improvementQueryRepository.findDuplicateEvidence(
				eq(1L), eq(List.of(10L)), org.mockito.ArgumentMatchers.any(), eq(0.07), eq(20)))
				.thenReturn(List.of());

		assertThatThrownBy(() -> service.reviewDuplicateSuspected(
				1L, new DuplicateSuspectedReviewRequest(List.of(10L))))
				.isInstanceOfSatisfying(FaqException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(FaqErrorCode.FAQ_DUPLICATE_EVIDENCE_NOT_FOUND));
		verifyNoInteractions(aiReviewLlmService);
	}

	@Test
	@DisplayName("검토 대상 FAQ를 중복 후보로 요청하면 저장소 조회 전에 거절한다")
	void 대상_FAQ를_중복_후보로_요청하면_조회하기_전에_거절한다() {
		assertThatThrownBy(() -> service.reviewDuplicateSuspected(
				1L, new DuplicateSuspectedReviewRequest(List.of(1L))))
				.isInstanceOfSatisfying(GlobalException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(CommonErrorCode.INVALID_PARAMETER));
		verifyNoInteractions(faqRepository, improvementQueryRepository, aiReviewLlmService);
	}

	@Test
	@DisplayName("검색 품질 검토는 답변 가능한 질문만 제안하고 최근 유사도를 소수점 네 자리로 표시한다")
	void 검색품질_검토는_답변_가능한_질문만_변형_후보로_표시하고_최근_유사도를_네_자리로_표시한다() {
		Faq target = faq(30L, "현재 포인트는 얼마인가요?", "현재 사용 가능한 포인트를 안내합니다.");
		List<FaqLowRagQualityQuestion> reviewQuestions = List.of(
				new FaqLowRagQualityQuestion("포인트 잔액 알려줘", 0.70123),
				new FaqLowRagQualityQuestion("포인트는 언제 초기화돼?", 0.71234));
		List<FaqLowRagQualityQuestion> recentQuestions = List.of(
				new FaqLowRagQualityQuestion("최근 포인트 질문", 0.74534),
				new FaqLowRagQualityQuestion("두 번째 최근 질문", 0.8));
		when(faqRepository.findActiveById(30L)).thenReturn(Optional.of(target));
		when(improvementQueryRepository.findLowRagQualityQuestions(
				eq(30L), org.mockito.ArgumentMatchers.any(), eq(3), eq(20)))
				.thenReturn(reviewQuestions);
		when(improvementQueryRepository.findRecentLowRagQualityQuestions(
				eq(30L), org.mockito.ArgumentMatchers.any(), eq(3), eq(5)))
				.thenReturn(recentQuestions);
		when(aiReviewLlmService.generateLowRagQualityRepresentativeAssessment(anyString()))
				.thenReturn(new LowRagQualityRepresentativeAssessment(
						false, "질문이 답변 범위를 충분히 나타냅니다.", "해당 없음"));
		when(aiReviewLlmService.generateLowRagQualityVariantAssessment(anyString(), eq(2)))
				.thenReturn(new LowRagQualityVariantAssessment(List.of(
						new LowRagQualityQuestionAssessment(1, true),
						new LowRagQualityQuestionAssessment(2, false))));

		var response = service.reviewLowRagQuality(30L);

		assertThat(response.recommendation()).contains("검색 질문 1개를 변형 질문으로 추가하세요");
		assertThat(response.details())
				.contains("최근 이 FAQ가 검색 결과에 포함된 질문(임베딩 유사도)")
				.contains("최근 포인트 질문(0.7453)")
				.contains("두 번째 최근 질문(0.8000)")
				.contains("포인트 잔액 알려줘")
				.doesNotContain("포인트는 언제 초기화돼?");
		verify(aiReviewLlmService).generateLowRagQualityRepresentativeAssessment(anyString());
		verify(aiReviewLlmService).generateLowRagQualityVariantAssessment(anyString(), eq(2));
	}

	@Test
	@DisplayName("검토할 검색 질문이 없으면 변형 질문 판정 LLM을 호출하지 않는다")
	void 검색_질문이_없으면_변형_질문_LLM을_호출하지_않는다() {
		Faq target = faq(40L, "질문", "답변");
		when(faqRepository.findActiveById(40L)).thenReturn(Optional.of(target));
		when(improvementQueryRepository.findLowRagQualityQuestions(
				eq(40L), org.mockito.ArgumentMatchers.any(), eq(3), eq(20)))
				.thenReturn(List.of());
		when(improvementQueryRepository.findRecentLowRagQualityQuestions(
				eq(40L), org.mockito.ArgumentMatchers.any(), eq(3), eq(5)))
				.thenReturn(List.of());
		when(aiReviewLlmService.generateLowRagQualityRepresentativeAssessment(anyString()))
				.thenReturn(new LowRagQualityRepresentativeAssessment(
						false, "질문이 답변 범위를 충분히 나타냅니다.", "해당 없음"));

		var response = service.reviewLowRagQuality(40L);

		assertThat(response.details()).contains("### 최근 이 FAQ가 검색 결과에 포함된 질문(임베딩 유사도)\n- 해당 없음");
		verify(aiReviewLlmService, never())
				.generateLowRagQualityVariantAssessment(anyString(), org.mockito.ArgumentMatchers.anyInt());
	}

	private Faq faq(Long id, String question, String answer) {
		return Faq.builder()
				.id(id)
				.question(question)
				.answer(answer)
				.version(1)
				.intent(Intent.GENERAL)
				.createdAt(LocalDateTime.now().minusDays(2))
				.updatedAt(LocalDateTime.now().minusDays(1))
				.build();
	}
}
