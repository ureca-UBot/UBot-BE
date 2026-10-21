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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
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
			return new AiReviewResponse(withSkippedNotice(
					"검토 입력 한도를 초과해 AI 검토를 진행하지 못했습니다.", skippedTypes));
		}
		return new AiReviewResponse(withSkippedNotice(
				aiReviewLlmService.generateReview(prompt.toString()), skippedTypes));
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

	private String withSkippedNotice(String answer, List<FaqImprovementType> skippedTypes) {
		if (skippedTypes.isEmpty()) {
			return answer;
		}
		String names = String.join(", ", skippedTypes.stream()
				.map(FaqImprovementType::getDescription).toList());
		return answer + "\n\n입력 한도로 인해 다음 태그는 AI 검토에 포함되지 않았습니다: " + names;
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
				question은 실제 검색에 쓰인 sub_question이며, 없는 과거 로그에서는 user_question입니다.
				질문은 후보별 최대 %d개입니다. pairCount는 두 FAQ가 함께 검색된 전체 횟수이며,
				질문 목록에서는 보관 기간에 따라 본문이 지워진 검색 로그를 제외했습니다.
				질문 자료가 부족하면 판단 근거가 부족하다고 밝히세요.
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
}
