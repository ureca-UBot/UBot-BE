package com.ubot.monitoring.faqmonitoring.service;

import com.ubot.common.GlobalException;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqDuplicateEvidence;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import com.ubot.monitoring.faqmonitoring.dto.request.AiReviewRequest;
import com.ubot.monitoring.faqmonitoring.dto.request.DuplicateSuspectedReviewRequest;
import com.ubot.monitoring.faqmonitoring.dto.response.AiReviewResponse;
import com.ubot.monitoring.faqmonitoring.repository.FaqMonitoringImprovementQueryRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiReviewService {
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final int MAX_DUPLICATE_CANDIDATES = 5;
	private static final int MAX_QUESTIONS_PER_CANDIDATE = 20;
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
	private static final List<FaqImprovementType> PRIORITY_ORDER = List.of(
			FaqImprovementType.DUPLICATE_SUSPECTED,
			FaqImprovementType.LOW_RAG_QUALITY,
			FaqImprovementType.LOW_USAGE
	);

	private final FaqRepository faqRepository;
	private final FaqMonitoringImprovementQueryRepository improvementQueryRepository;
	private final AiReviewLlmService aiReviewLlmService;

	@Value("${faq-monitoring.improvement.low-gap-threshold}")
	private double lowGapThreshold;

	public AiReviewResponse review(Long faqId, AiReviewRequest request) {
		long startedAt = System.nanoTime();
		List<FaqImprovementType> requestedTypes = request == null ? null : request.improvementTypes();
		int duplicateCandidateCount = request == null || request.duplicateSuspected() == null
				|| request.duplicateSuspected().candidateFaqIds() == null
				? 0 : request.duplicateSuspected().candidateFaqIds().size();
		log.info("FAQ AI 검토를 시작합니다: faqId={}, 요청태그={}, 중복후보수={}",
				faqId, requestedTypes, duplicateCandidateCount);
		try {
			AiReviewResponse response = reviewInternal(faqId, request);
			log.info("FAQ AI 검토를 완료했습니다: faqId={}, 요청태그={}, 처리시간={}ms",
					faqId, requestedTypes, elapsedMillis(startedAt));
			return response;
		} catch (RuntimeException exception) {
			log.warn("FAQ AI 검토에 실패했습니다: faqId={}, 요청태그={}, 처리시간={}ms, 사유={}",
					faqId, requestedTypes, elapsedMillis(startedAt), exception.getMessage());
			throw exception;
		}
	}

	private AiReviewResponse reviewInternal(Long faqId, AiReviewRequest request) {
		List<FaqImprovementType> improvementTypes = request.improvementTypes();
		if (improvementTypes == null || improvementTypes.isEmpty() || improvementTypes.contains(null)) {
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
		EnumSet<FaqImprovementType> requested = EnumSet.copyOf(improvementTypes);
		if (requested.contains(FaqImprovementType.DUPLICATE_SUSPECTED)) {
			validateDuplicateRequest(faqId, request.duplicateSuspected());
		}
		Faq faq = faqRepository.findActiveById(faqId)
				.orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
		List<FaqImprovementType> orderedTypes = PRIORITY_ORDER.stream().filter(requested::contains).toList();

		StringBuilder prompt = new StringBuilder("""
				[검토 대상 FAQ]
				FAQ ID: %d
				버전: %d
				질문: %s
				답변: %s
				인텐트: %s
				
				""".formatted(faq.getId(), faq.getVersion(), faq.getQuestion(), faq.getAnswer(), faq.getIntent()));
		if (!aiReviewLlmService.fits(prompt.toString())) {
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER, "FAQ 내용이 AI 검토 입력 한도를 초과했습니다.");
		}

		List<FaqImprovementType> reviewedTypes = new ArrayList<>();
		for (FaqImprovementType type : orderedTypes) {
			String section = switch (type) {
				case DUPLICATE_SUSPECTED -> buildDuplicateSection(faqId, request.duplicateSuspected());
				case LOW_RAG_QUALITY -> buildLowRagQualitySection(faqId);
				case LOW_USAGE -> buildLowUsageSection(faqId);
			};
			if (!aiReviewLlmService.fits(prompt.toString() + section)) {
				break;
			}
			prompt.append(section);
			reviewedTypes.add(type);
		}

		List<FaqImprovementType> skippedTypes = orderedTypes.subList(reviewedTypes.size(), orderedTypes.size());
		if (reviewedTypes.isEmpty()) {
			log.warn("FAQ AI 검토의 LLM 호출을 생략했습니다: faqId={}, 사유=입력 한도 초과, 제외태그={}",
					faqId, skippedTypes);
			return withSkippedNotice(new AiReviewResponse(
					"검토 입력을 줄인 뒤 AI 검토를 다시 요청하세요.",
					"검토 입력 한도를 초과해 AI 검토를 진행하지 못했습니다."), skippedTypes);
		}
		log.info("FAQ AI 검토 프롬프트를 구성했습니다: faqId={}, 포함태그={}, 제외태그={}, 입력문자수={}",
				faqId, reviewedTypes, skippedTypes, prompt.length());
		log.info("FAQ AI 검토 LLM 호출을 시작합니다: faqId={}", faqId);
		return withSkippedNotice(aiReviewLlmService.generateReview(prompt.toString()), skippedTypes);
	}

	private void validateDuplicateRequest(Long faqId, DuplicateSuspectedReviewRequest duplicateRequest) {
		if (duplicateRequest == null || duplicateRequest.candidateFaqIds() == null
				|| duplicateRequest.candidateFaqIds().contains(null)) {
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
		List<Long> candidateIds = duplicateRequest.candidateFaqIds();
		Set<Long> uniqueCandidateIds = new HashSet<>(candidateIds);
		if (candidateIds.size() > MAX_DUPLICATE_CANDIDATES || uniqueCandidateIds.size() != candidateIds.size()
				|| uniqueCandidateIds.contains(faqId) || candidateIds.stream().anyMatch(id -> id <= 0)) {
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
	}

	private AiReviewResponse withSkippedNotice(AiReviewResponse response, List<FaqImprovementType> skippedTypes) {
		if (skippedTypes.isEmpty()) {
			return response;
		}
		String names = String.join(", ", skippedTypes.stream()
				.map(FaqImprovementType::getDescription).toList());
		return new AiReviewResponse(response.recommendation(),
				response.details() + "\n\n입력 한도로 인해 다음 태그는 AI 검토에 포함되지 않았습니다: " + names);
	}

	private String buildDuplicateSection(Long faqId, DuplicateSuspectedReviewRequest duplicateRequest) {
		List<Long> candidateIds = duplicateRequest.candidateFaqIds();
		if (candidateIds.isEmpty()) {
			return "[FAQ 중복 의심]\n전달된 중복 후보 FAQ가 없습니다. 중복 여부를 단정하지 마세요.\n\n";
		}
		LocalDateTime evaluatedAt = LocalDateTime.now(SERVICE_ZONE);
		Map<Long, Faq> activeFaqs = faqRepository.findAllById(candidateIds).stream()
				.filter(faq -> faq.getDeletedAt() == null)
				.collect(Collectors.toMap(Faq::getId, Function.identity()));
		List<FaqDuplicateEvidence> evidence = improvementQueryRepository.findDuplicateEvidence(
				faqId, candidateIds, evaluatedAt, lowGapThreshold, MAX_QUESTIONS_PER_CANDIDATE);
		Map<Long, FaqDuplicateEvidence> evidenceByCandidate = evidence.stream()
				.collect(Collectors.toMap(FaqDuplicateEvidence::candidateFaqId, Function.identity()));

		List<DuplicateCandidateMaterial> materials = new ArrayList<>();
		for (Long candidateId : candidateIds) {
			Faq faq = activeFaqs.get(candidateId);
			if (faq == null) {
				continue;
			}
			FaqDuplicateEvidence candidateEvidence = evidenceByCandidate.get(candidateId);
			long pairCount = candidateEvidence == null ? 0 : candidateEvidence.pairCount();
			List<String> questionMaterials = candidateEvidence == null ? List.of() : candidateEvidence.questions();
			materials.add(new DuplicateCandidateMaterial(
					faq.getId(), faq.getQuestion(), faq.getAnswer(), pairCount, questionMaterials));
		}
		if (materials.isEmpty()) {
			return "[FAQ 중복 의심]\n현재 비교할 수 있는 활성 후보 FAQ가 없습니다. 중복이라고 단정하지 마세요.\n\n";
		}
		return """
				[FAQ 중복 의심]
				다음 후보를 각각 독립적으로 검토하세요. 각 검색 질문에 대상 FAQ와 후보 FAQ의 답변이
				모두 적용되는지, 한쪽에만 적용되는지, 둘 다 부적절한지, 판단할 수 없는지 구분하세요.
				질문과 답변의 적용 조건이 다르면 검색에 함께 등장했다는 이유만으로 통합을 제안하지 마세요.
				대상 FAQ뿐 아니라 후보 FAQ 각각에도 질문 또는 답변 수정이 필요한지 판단하고,
				필요하다면 무엇을 어떻게 바꿀지 권고하세요. 근거가 없으면 수정안을 만들어내지 마세요.
				question은 실제 검색에 쓰인 sub_question이며, 없는 과거 로그에서는 user_question입니다.
				질문은 후보별 최대 %d개입니다. pairCount는 두 FAQ가 함께 검색된 전체 횟수이며,
				질문 목록에서는 보관 기간에 따라 본문이 지워진 검색 로그를 제외했습니다.
				질문 자료가 부족하면 판단 근거가 부족하다고 밝히세요.
				판단 결과는 다음 기준으로 고르세요.
				- 중복: 한 FAQ의 질문 범위와 답변이 다른 FAQ에 완전히 포함되어 별도로 유지할 실익이 없습니다.
				  일반적인 FAQ와 그 범위에 완전히 포함되는 구체적인 사례 FAQ도, 별도의 조건이나 다른 답변이 없다면 중복입니다.
				- 구분 필요: 주제는 유사하지만 적용 조건이나 답변이 달라 두 FAQ를 유지하되 질문에서 범위를 명확히 해야 합니다.
				- 중복 아님: 서로 다른 사용자 요구를 해결하며 각각 유지할 이유가 있습니다.
				- 판단 근거 부족: 제공된 질문과 답변만으로 적용 범위를 판단할 수 없습니다.
				판단 결과에 따라 다음 조치 규칙을 반드시 지키세요.
				- 중복: 유지할 FAQ와 통합·삭제 또는 비활성화를 검토할 FAQ를 명시하세요. 대상과 후보 모두에
				  수정 불필요 또는 현행 유지를 권고하면 안 됩니다.
				- 구분 필요: 대상 FAQ와 후보 FAQ 각각에서 구분해야 할 조건과 수정 방향을 제안하세요.
				- 중복 아님: 각각 유지할 이유와 각 FAQ의 수정 필요 여부를 적으세요.
				- 판단 근거 부족: 단정적인 수정안을 만들지 말고 추가로 확인할 자료를 적으세요.
				판단 근거 안에서 선택한 판단 결과를 뒤집거나 서로 다른 결론을 함께 쓰지 마세요.
				details의 이 태그 부분은 반드시 다음 Markdown 구조를 사용하세요.
				## 중복 의심 FAQ 판정 결과
				### 후보 {순번}: FAQ {후보 FAQ ID}
				#### 후보 FAQ 정보
				- **질문**: {후보 질문}
				- **답변**: {후보 답변}
				- **함께 검색된 횟수**: {pairCount}
				- **대표 유저 질문**: {함께 검색된 질문 중 판단에 가장 적합한 질문 정확히 1개}
				#### 판정
				- **판단 결과**: **{중복, 구분 필요, 중복 아님, 판단 근거 부족 중 하나}**
				- **판단 근거**: {대상 FAQ와 후보 FAQ가 대표 질문에 적용되는지를 비교한 근거}
				#### 조치 권고
				- **대상 FAQ {대상 FAQ ID}**: {유지, 수정, 통합, 삭제 검토 등 구체적인 조치}
				- **후보 FAQ {후보 FAQ ID}**: {유지, 수정, 통합, 삭제 검토 등 구체적인 조치}
				대표 유저 질문에는 질문을 정확히 한 개만 쓰고, 여러 질문을 쉼표나 목록으로 나열하지 마세요.
				후보마다 위 항목을 빠짐없이 한 번씩 작성하세요.
				후보 FAQ 자료(JSON): %s

				""".formatted(MAX_QUESTIONS_PER_CANDIDATE, JSON_MAPPER.writeValueAsString(materials));
	}

	private record DuplicateCandidateMaterial(
			Long id, String question, String answer, long pairCount,
			List<String> coRetrievedQuestions
	) {
	}

	private String buildLowRagQualitySection(Long faqId) {
		// 낮은 유사도로 검색된 실제 질문 자료는 후속 단계에서 추가합니다.
		return "[검색 품질 낮음]\n실제 검색 질문과 유사도 자료는 아직 제공되지 않았습니다. 원인을 단정하지 마세요.\n\n";
	}

	private String buildLowUsageSection(Long faqId) {
		// 사용 추이와 관련 사용자 질문 자료는 후속 단계에서 추가합니다.
		return "[사용도 낮음]\n사용 추이와 관련 질문 자료는 아직 제공되지 않았습니다. 수요가 없다고 단정하지 마세요.\n\n";
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
