package com.ubot.monitoring.faqmonitoring.service;

import com.ubot.common.GlobalException;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import com.ubot.monitoring.faqmonitoring.dto.response.AiReviewResponse;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiReviewService {
	private static final List<FaqImprovementType> PRIORITY_ORDER = List.of(
			FaqImprovementType.DUPLICATE_SUSPECTED,
			FaqImprovementType.LOW_RAG_QUALITY,
			FaqImprovementType.LOW_USAGE
	);

	private final FaqRepository faqRepository;
	private final AiReviewLlmService aiReviewLlmService;

	public AiReviewResponse review(Long faqId, List<FaqImprovementType> improvementTypes) {
		if (improvementTypes == null || improvementTypes.isEmpty() || improvementTypes.contains(null)) {
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
		Faq faq = faqRepository.findActiveById(faqId)
				.orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
		EnumSet<FaqImprovementType> requested = EnumSet.copyOf(improvementTypes);
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
				case DUPLICATE_SUSPECTED -> buildDuplicateSection(faqId);
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

	private String withSkippedNotice(String answer, List<FaqImprovementType> skippedTypes) {
		if (skippedTypes.isEmpty()) {
			return answer;
		}
		String names = String.join(", ", skippedTypes.stream()
				.map(FaqImprovementType::getDescription).toList());
		return answer + "\n\n입력 한도로 인해 다음 태그는 AI 검토에 포함되지 않았습니다: " + names;
	}

	private String buildDuplicateSection(Long faqId) {
		// 후보 FAQ와 두 FAQ가 함께 검색된 질문 자료는 후속 단계에서 추가합니다.
		return "[FAQ 중복 의심]\n비교 대상 FAQ와 사용자 질문 자료는 아직 제공되지 않았습니다. 중복 여부를 단정하지 마세요.\n\n";
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
