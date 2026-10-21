package com.ubot.monitoring.faqmonitoring.service;

import com.ubot.common.GlobalException;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.monitoring.faqmonitoring.config.AiReviewProperties;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateCandidateAssessment;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateReviewAction;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateSuspectedAssessment;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqDuplicateEvidence;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqLowRagQualityQuestion;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.LowRagQualityQuestionAssessment;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.LowRagQualityRepresentativeAssessment;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.LowRagQualityVariantAssessment;
import com.ubot.monitoring.faqmonitoring.dto.request.DuplicateSuspectedReviewRequest;
import com.ubot.monitoring.faqmonitoring.dto.response.AiReviewResponse;
import com.ubot.monitoring.faqmonitoring.repository.FaqMonitoringImprovementQueryRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
	private final FaqRepository faqRepository;
	private final FaqMonitoringImprovementQueryRepository improvementQueryRepository;
	private final AiReviewLlmService aiReviewLlmService;
	private final AiReviewProperties aiReviewProperties;

	@Value("${faq-monitoring.improvement.low-gap-threshold}")
	private double lowGapThreshold;

	@Value("${faq-monitoring.improvement.top-k}")
	private int improvementTopK;

	public AiReviewResponse reviewDuplicateSuspected(
			Long faqId,
			DuplicateSuspectedReviewRequest request
	) {
		validateDuplicateRequest(faqId, request);
		long startedAt = System.nanoTime();
		log.info("FAQ AI 검토를 시작합니다: faqId={}, 검토태그={}, 중복후보수={}",
				faqId, FaqImprovementType.DUPLICATE_SUSPECTED, request.candidateFaqIds().size());
		try {
			Faq faq = findActiveFaq(faqId);
			AiReviewResponse response = generateDuplicateSuspectedReview(faq, request);
			logReviewCompleted(faqId, FaqImprovementType.DUPLICATE_SUSPECTED, startedAt);
			return response;
		} catch (RuntimeException exception) {
			logReviewFailed(faqId, FaqImprovementType.DUPLICATE_SUSPECTED, startedAt, exception);
			throw exception;
		}
	}

	public AiReviewResponse reviewLowRagQuality(Long faqId) {
		long startedAt = System.nanoTime();
		log.info("FAQ AI 검토를 시작합니다: faqId={}, 검토태그={}",
				faqId, FaqImprovementType.LOW_RAG_QUALITY);
		try {
			Faq faq = findActiveFaq(faqId);
			List<FaqLowRagQualityQuestion> questions = improvementQueryRepository.findLowRagQualityQuestions(
					faq.getId(), LocalDateTime.now(SERVICE_ZONE), improvementTopK,
					aiReviewProperties.getMaxLowRagQualityQuestions());
			AiReviewResponse response = generateLowRagQualityReview(faq, questions);
			logReviewCompleted(faqId, FaqImprovementType.LOW_RAG_QUALITY, startedAt);
			return response;
		} catch (RuntimeException exception) {
			logReviewFailed(faqId, FaqImprovementType.LOW_RAG_QUALITY, startedAt, exception);
			throw exception;
		}
	}

	private Faq findActiveFaq(Long faqId) {
		return faqRepository.findActiveById(faqId)
				.orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
	}

	private AiReviewResponse generateDuplicateSuspectedReview(
			Faq faq,
			DuplicateSuspectedReviewRequest request
	) {
		List<DuplicateCandidateMaterial> materials = findDuplicateCandidateMaterials(faq.getId(), request);
		String basePrompt = buildBasePrompt(faq);
		String prompt = basePrompt + buildDuplicateSection(materials);
		log.info("FAQ AI 검토 프롬프트를 구성했습니다: faqId={}, 검토태그={}, 입력문자수={}",
				faq.getId(), FaqImprovementType.DUPLICATE_SUSPECTED, prompt.length());
		log.info("FAQ AI 검토 LLM 호출을 시작합니다: faqId={}, 검토태그={}",
				faq.getId(), FaqImprovementType.DUPLICATE_SUSPECTED);
		DuplicateSuspectedAssessment assessment = aiReviewLlmService.generateDuplicateSuspectedReview(
				prompt,
				materials.stream().map(DuplicateCandidateMaterial::id).toList()
		);
		return toDuplicateSuspectedResponse(faq.getId(), materials, assessment);
	}

	private String buildBasePrompt(Faq faq) {
		return """
				[검토 대상 FAQ]
				FAQ ID: %d
				버전: %d
				질문: %s
				답변: %s
				인텐트: %s
				
				""".formatted(faq.getId(), faq.getVersion(), faq.getQuestion(), faq.getAnswer(), faq.getIntent());
	}

	private void logReviewCompleted(Long faqId, FaqImprovementType improvementType, long startedAt) {
		log.info("FAQ AI 검토를 완료했습니다: faqId={}, 검토태그={}, 처리시간={}ms",
				faqId, improvementType, elapsedMillis(startedAt));
	}

	private void logReviewFailed(
			Long faqId,
			FaqImprovementType improvementType,
			long startedAt,
			RuntimeException exception
	) {
		log.warn("FAQ AI 검토에 실패했습니다: faqId={}, 검토태그={}, 처리시간={}ms, 사유={}",
				faqId, improvementType, elapsedMillis(startedAt), exception.getMessage());
	}

	private void validateDuplicateRequest(Long faqId, DuplicateSuspectedReviewRequest duplicateRequest) {
		if (duplicateRequest == null || duplicateRequest.candidateFaqIds() == null
				|| duplicateRequest.candidateFaqIds().contains(null)) {
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
		List<Long> candidateIds = duplicateRequest.candidateFaqIds();
		Set<Long> uniqueCandidateIds = new HashSet<>(candidateIds);
		if (candidateIds.isEmpty() || candidateIds.size() > aiReviewProperties.getMaxDuplicateCandidates()
				|| uniqueCandidateIds.size() != candidateIds.size()
				|| uniqueCandidateIds.contains(faqId) || candidateIds.stream().anyMatch(id -> id <= 0)) {
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
	}

	private List<DuplicateCandidateMaterial> findDuplicateCandidateMaterials(
			Long faqId,
			DuplicateSuspectedReviewRequest duplicateRequest
	) {
		List<Long> candidateIds = duplicateRequest.candidateFaqIds();
		LocalDateTime evaluatedAt = LocalDateTime.now(SERVICE_ZONE);
		Map<Long, Faq> activeFaqs = faqRepository.findAllById(candidateIds).stream()
				.filter(faq -> faq.getDeletedAt() == null)
				.collect(Collectors.toMap(Faq::getId, Function.identity()));
		if (activeFaqs.isEmpty()) {
			throw new FaqException(FaqErrorCode.FAQ_REVIEW_CANDIDATE_NOT_FOUND);
		}
		List<Long> activeCandidateIds = candidateIds.stream()
				.filter(activeFaqs::containsKey)
				.toList();
		List<FaqDuplicateEvidence> evidence = improvementQueryRepository.findDuplicateEvidence(
				faqId, activeCandidateIds, evaluatedAt, lowGapThreshold,
				aiReviewProperties.getMaxQuestionsPerDuplicateCandidate());
		Map<Long, FaqDuplicateEvidence> evidenceByCandidate = evidence.stream()
				.collect(Collectors.toMap(FaqDuplicateEvidence::candidateFaqId, Function.identity()));

		List<DuplicateCandidateMaterial> materials = new ArrayList<>();
		for (Long candidateId : candidateIds) {
			Faq faq = activeFaqs.get(candidateId);
			if (faq == null) {
				continue;
			}
			FaqDuplicateEvidence candidateEvidence = evidenceByCandidate.get(candidateId);
			if (candidateEvidence == null || candidateEvidence.pairCount() <= 0
					|| candidateEvidence.questions() == null || candidateEvidence.questions().isEmpty()) {
				throw new FaqException(FaqErrorCode.FAQ_DUPLICATE_EVIDENCE_NOT_FOUND);
			}
			materials.add(new DuplicateCandidateMaterial(
					faq.getId(), faq.getQuestion(), faq.getAnswer(),
					candidateEvidence.pairCount(), candidateEvidence.questions()));
		}
		return List.copyOf(materials);
	}

	private String buildDuplicateSection(List<DuplicateCandidateMaterial> materials) {
		return """
				[FAQ 중복 의심]
				다음 후보를 각각 독립적으로 검토하세요. 각 검색 질문에 대상 FAQ와 후보 FAQ의 답변이
				모두 적용되는지, 한쪽에만 적용되는지, 둘 다 부적절한지, 판단할 수 없는지 구분하세요.
				질문과 답변의 적용 조건이 다르면 검색에 함께 등장했다는 이유만으로 통합을 제안하지 마세요.
				대상 FAQ뿐 아니라 후보 FAQ 각각에도 질문 또는 답변 수정이 필요한지 판단하고,
				필요하다면 무엇을 어떻게 바꿀지 권고하세요. 근거가 없으면 수정안을 만들어내지 마세요.
				question은 실제 검색에 쓰인 sub_question이며, 없는 과거 로그에서는 user_question입니다.
				질문은 후보별 최대 %d개입니다. pairCount는 두 FAQ가 함께 검색된 전체 횟수이며,
				질문 목록에서는 보관 기간에 따라 본문이 지워진 검색 로그를 제외했고, 서로 다른 질문을
				최근 검색된 순서로 정렬했습니다.
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
				판단 결과에 대응하는 result와 action 코드는 시스템 지침에 정의된 조합대로 작성하세요.
				후보마다 구조화 응답의 모든 항목을 빠짐없이 한 번씩 작성하세요.
				후보 FAQ 자료(JSON): %s

				""".formatted(
				aiReviewProperties.getMaxQuestionsPerDuplicateCandidate(),
				JSON_MAPPER.writeValueAsString(materials)
		);
	}

	private AiReviewResponse toDuplicateSuspectedResponse(
			Long targetFaqId,
			List<DuplicateCandidateMaterial> materials,
			DuplicateSuspectedAssessment assessment
	) {
		Map<Long, DuplicateCandidateAssessment> assessmentByCandidate = assessment.candidateAssessments().stream()
				.collect(Collectors.toMap(DuplicateCandidateAssessment::candidateFaqId, Function.identity()));
		String recommendation = buildDuplicateSuspectedRecommendation(targetFaqId, materials, assessmentByCandidate);
		StringBuilder details = new StringBuilder("## 중복 의심 FAQ 판정 결과\n");
		for (int index = 0; index < materials.size(); index++) {
			DuplicateCandidateMaterial material = materials.get(index);
			DuplicateCandidateAssessment candidate = assessmentByCandidate.get(material.id());
			details.append("### 후보 ").append(index + 1).append(": FAQ ").append(material.id()).append('\n');
			details.append("#### 후보 FAQ 정보\n");
			details.append("- **질문**: ").append(material.question()).append('\n');
			details.append("- **답변**: ").append(material.answer()).append('\n');
			details.append("- **함께 검색된 횟수**: ").append(material.pairCount()).append('\n');
			details.append("#### 함께 검색된 유저 질문\n");
			int displayedQuestionCount = Math.min(
					material.coRetrievedQuestions().size(),
					aiReviewProperties.getMaxDisplayedQuestionsPerDuplicateCandidate()
			);
			for (int questionIndex = 0; questionIndex < displayedQuestionCount; questionIndex++) {
				details.append("- **질문 ").append(questionIndex + 1).append("**: ")
						.append(material.coRetrievedQuestions().get(questionIndex)).append('\n');
			}
			details.append("#### 판정\n");
			details.append("- **판단 결과**: **").append(candidate.result().getDescription()).append("**\n");
			details.append("- **권고 조치**: **").append(candidate.action().getDescription()).append("**\n");
			details.append("- **판단 근거**: ").append(candidate.reason().strip()).append('\n');
			details.append("#### 조치 권고\n");
			details.append("- **대상 FAQ ").append(targetFaqId).append("**: ")
					.append(candidate.targetFaqRecommendation().strip()).append('\n');
			details.append("- **후보 FAQ ").append(material.id()).append("**: ")
					.append(candidate.candidateFaqRecommendation().strip());
			if (index < materials.size() - 1) {
				details.append("\n\n");
			}
		}
		return new AiReviewResponse(recommendation, details.toString());
	}

	private String buildDuplicateSuspectedRecommendation(
			Long targetFaqId,
			List<DuplicateCandidateMaterial> materials,
			Map<Long, DuplicateCandidateAssessment> assessmentByCandidate
	) {
		List<String> actionClauses = new ArrayList<>(materials.size());
		for (DuplicateCandidateMaterial material : materials) {
			DuplicateReviewAction action = assessmentByCandidate.get(material.id()).action();
			actionClauses.add(switch (action) {
				case MERGE_CANDIDATE_INTO_TARGET ->
						"FAQ %d를 FAQ %d에 통합".formatted(material.id(), targetFaqId);
				case MERGE_TARGET_INTO_CANDIDATE ->
						"FAQ %d를 FAQ %d에 통합".formatted(targetFaqId, material.id());
				case CLARIFY_SCOPE ->
						"FAQ %d와 FAQ %d의 질문 범위를 명확히 구분".formatted(targetFaqId, material.id());
				case KEEP_SEPARATE ->
						"FAQ %d와 FAQ %d를 각각 유지".formatted(targetFaqId, material.id());
				case COLLECT_MORE_EVIDENCE ->
						"FAQ %d와 FAQ %d의 중복 판단 근거를 추가로 확인".formatted(targetFaqId, material.id());
			});
		}
		return String.join("하고, ", actionClauses) + "하세요.";
	}

	private record DuplicateCandidateMaterial(
			Long id, String question, String answer, long pairCount,
			List<String> coRetrievedQuestions
	) {
	}

	private AiReviewResponse generateLowRagQualityReview(
			Faq faq,
			List<FaqLowRagQualityQuestion> questions
	) {
		List<LowRagQualityQuestionMaterial> materials = new ArrayList<>(questions.size());
		for (int index = 0; index < questions.size(); index++) {
			FaqLowRagQualityQuestion question = questions.get(index);
			materials.add(new LowRagQualityQuestionMaterial(
					index + 1, question.question(), question.similarity()));
		}

		String basePrompt = buildBasePrompt(faq);
		String representativePrompt = basePrompt + buildRepresentativeQuestionPrompt();
		String variantPrompt = basePrompt + buildVariantQuestionPrompt(materials);
		log.info("FAQ AI 검토 프롬프트를 구성했습니다: faqId={}, 검토태그={}, 대표질문입력문자수={}, 변형질문입력문자수={}",
				faq.getId(), FaqImprovementType.LOW_RAG_QUALITY,
				representativePrompt.length(), variantPrompt.length());
		log.info("FAQ AI 검토 LLM 호출을 시작합니다: faqId={}, 검토태그={}, 검토항목=대표 질문",
				faq.getId(), FaqImprovementType.LOW_RAG_QUALITY);
		LowRagQualityRepresentativeAssessment representativeAssessment =
				aiReviewLlmService.generateLowRagQualityRepresentativeAssessment(representativePrompt);

		LowRagQualityVariantAssessment variantAssessment;
		if (questions.isEmpty()) {
			variantAssessment = new LowRagQualityVariantAssessment(List.of());
		} else {
			log.info("FAQ AI 검토 LLM 호출을 시작합니다: faqId={}, 검토태그={}, 검토항목=변형 질문",
					faq.getId(), FaqImprovementType.LOW_RAG_QUALITY);
			variantAssessment = aiReviewLlmService.generateLowRagQualityVariantAssessment(
					variantPrompt, questions.size());
		}
		return toLowRagQualityResponse(faq, questions, representativeAssessment, variantAssessment);
	}

	private String buildRepresentativeQuestionPrompt() {
		return """
				[대표 질문 검토]
				검토 대상 FAQ의 대표 질문과 답변만 비교하세요. 검색 질문은 제공되지 않으며 판단에 사용해서도 안 됩니다.
				답변이 제공하는 주요 대상, 조건, 기간 또는 기능을 대표 질문이 충분히 나타내는지 판단하세요.
				대표 질문이 답변 범위를 충분히 나타내지 못하면 needsUpdate를 true로 설정하고 수정안을 한 개 제시하세요.
				대표 질문이 답변 범위를 충분히 나타내면 needsUpdate를 false로 설정하세요.
				수정 여부와 관계없이 reason에는 질문과 답변의 범위를 비교한 구체적인 판단 근거를 작성하세요.
				needsUpdate가 false일 때만 suggestedQuestion에 '해당 없음'을 작성하세요.

				""";
	}

	private String buildVariantQuestionPrompt(List<LowRagQualityQuestionMaterial> questions) {
		return """
				[변형 질문 검토]
				- 검색 질문은 현재 FAQ 버전 이후 이 FAQ가 검색 결과 상위 %d개 안에 등장한 서로 다른 질문이며,
				  유사도 오름차순으로 최대 %d개입니다.
				- 각 질문이 요구하는 정보에 현재 FAQ 답변이 직접적이고 완전하게 답할 때만 answerableByCurrentFaq를 true로 설정하세요.
				- 답변에 요구 정보가 없거나 일부만 답하거나 추론해야 하면 false로 설정하세요.
				- 예: 답변이 사용 가능한 포인트 잔액만 안내한다면 잔액 질문은 true이지만, 포인트 초기화 시점,
				  유효기간 또는 적립 방법 질문은 false입니다.
				- 질문 내용과 유사도를 응답에 복사하지 말고 모든 questionNumber와 boolean 판정만 반환하세요.
				검색 품질 검토 질문(JSON): %s

				""".formatted(
				improvementTopK,
				aiReviewProperties.getMaxLowRagQualityQuestions(),
				JSON_MAPPER.writeValueAsString(questions)
		);
	}

	private AiReviewResponse toLowRagQualityResponse(
			Faq faq,
			List<FaqLowRagQualityQuestion> questions,
			LowRagQualityRepresentativeAssessment representativeAssessment,
			LowRagQualityVariantAssessment variantAssessment
	) {
		Map<Integer, Boolean> answerableByQuestionNumber = variantAssessment.questionAssessments().stream()
				.collect(Collectors.toMap(
						LowRagQualityQuestionAssessment::questionNumber,
						LowRagQualityQuestionAssessment::answerableByCurrentFaq
				));
		List<FaqLowRagQualityQuestion> variantCandidates = new ArrayList<>();
		for (int index = 0; index < questions.size(); index++) {
			if (Boolean.TRUE.equals(answerableByQuestionNumber.get(index + 1))) {
				variantCandidates.add(questions.get(index));
			}
		}

		boolean questionNeedsUpdate = Boolean.TRUE.equals(representativeAssessment.needsUpdate());
		String suggestedQuestion = questionNeedsUpdate
				? representativeAssessment.suggestedQuestion().strip()
				: "해당 없음";
		String recommendation = buildLowRagQualityRecommendation(
				faq.getId(), questionNeedsUpdate, suggestedQuestion, variantCandidates.size());
		String details = buildLowRagQualityDetails(
				questionNeedsUpdate,
				representativeAssessment.reason().strip(),
				suggestedQuestion,
				variantCandidates
		);
		return new AiReviewResponse(recommendation, details);
	}

	private String buildLowRagQualityRecommendation(
			Long faqId,
			boolean questionNeedsUpdate,
			String suggestedQuestion,
			int variantCandidateCount
	) {
		if (questionNeedsUpdate && variantCandidateCount > 0) {
			return "FAQ %d의 대표 질문을 '%s'(으)로 수정하고 검색 질문 %d개를 변형 질문으로 추가하세요."
					.formatted(faqId, suggestedQuestion, variantCandidateCount);
		}
		if (questionNeedsUpdate) {
			return "FAQ %d의 대표 질문을 '%s'(으)로 수정하세요.".formatted(faqId, suggestedQuestion);
		}
		if (variantCandidateCount > 0) {
			return "FAQ %d에 답변 가능한 검색 질문 %d개를 변형 질문으로 추가하세요."
					.formatted(faqId, variantCandidateCount);
		}
		return "FAQ %d는 대표 질문 수정이나 변형 질문 추가가 필요하지 않습니다.".formatted(faqId);
	}

	private String buildLowRagQualityDetails(
			boolean questionNeedsUpdate,
			String representativeQuestionReason,
			String suggestedQuestion,
			List<FaqLowRagQualityQuestion> variantCandidates
	) {
		StringBuilder details = new StringBuilder("""
				## 검색 품질 낮음 FAQ 판정 결과
				### 대표 질문 검토
				- **판단 결과**: **%s**
				- **판단 근거**: %s
				- **대표 질문 수정안**: %s
				### 변형 질문 검토
				- **판단 결과**: **%s**
				#### 추가할 변형 질문
				""".formatted(
				questionNeedsUpdate ? "대표 질문 수정 필요" : "대표 질문 수정 불필요",
				representativeQuestionReason,
				suggestedQuestion,
				variantCandidates.isEmpty() ? "변형 질문 추가 불필요" : "변형 질문 추가 필요"
		));
		if (variantCandidates.isEmpty()) {
			details.append("- 해당 없음\n");
		} else {
			for (int index = 0; index < variantCandidates.size(); index++) {
				FaqLowRagQualityQuestion candidate = variantCandidates.get(index);
				details.append("- **질문 ").append(index + 1).append("**: ")
						.append(candidate.question()).append('\n');
				details.append("- **질문 ").append(index + 1).append(" 유사도**: ")
						.append(candidate.similarity()).append('\n');
			}
		}
		details.append("- **판단 근거**: ");
		if (variantCandidates.isEmpty()) {
			details.append("검토한 검색 질문 중 현재 FAQ 답변으로 완전히 처리할 수 있는 질문이 확인되지 않았습니다.\n");
			details.append("- **조치 권고**: 변형 질문 추가 불필요");
		} else {
			details.append("위 질문들은 현재 FAQ 답변으로 완전히 처리할 수 있어 변형 질문 후보에 해당합니다.\n");
			details.append("- **조치 권고**: 위 검색 질문 ")
					.append(variantCandidates.size()).append("개를 변형 질문으로 추가");
		}
		return details.toString();
	}

	private record LowRagQualityQuestionMaterial(
			int questionNumber,
			String question,
			double similarity
	) {
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
