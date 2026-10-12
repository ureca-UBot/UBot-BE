package com.ubot.faq.service;

import com.ubot.faq.config.FaqCacheProperties;
import com.ubot.faq.config.FaqSemanticCacheProperties;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.dto.response.SemanticFaqCandidateResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.policy.ReviewedFaqPolicy;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaqSemanticCacheService {
    private final FaqSemanticCacheProperties properties;
    private final FaqCacheProperties cacheProperties;
    private final ReviewedFaqPolicy reviewedPolicy = new ReviewedFaqPolicy();
    // Conservative exclusions, not a complete intent/completeness classifier.
    private static final Pattern EXCLUDED = Pattern.compile(
            "그리고|그런데|아니라|말고|추가로|더불어|아까|방금|위에서|그거|이거|그것|이것"
            + "|(?:조회|확인|변경|신청|해지|발급|예약)\\s*(?:해\\s*줘|해주세요|해줘|해줄)"
            + "|(?:알려|설명|궁금).*(?:또|도\\s*(?:알려|설명|궁금))|[;\\r\\n]");

    public boolean shouldSearch(String question, boolean research) {
        return properties.isEnabled() && cacheProperties.isEnabled() && !research
                && question != null && !question.isBlank() && question.length() <= 160
                && question.chars().filter(c -> c == '?' || c == '？').count() <= 1
                && !EXCLUDED.matcher(question).find()
                && (!properties.isReviewedPolicyEnabled() || reviewedPolicy.supports(question));
    }

    public String getEmbeddingModel() {
        return properties.getEmbeddingModel();
    }

    public Optional<FaqSearchResponseDto> select(Long attemptId, List<SemanticFaqCandidateResponseDto> candidates) {
        return select(attemptId, null, candidates);
    }

    public Optional<FaqSearchResponseDto> select(Long attemptId, String question, List<SemanticFaqCandidateResponseDto> candidates) {
        if (!properties.isEnabled() || !cacheProperties.isEnabled()) {
            return Optional.empty();
        }
        if (properties.isReviewedPolicyEnabled()) {
            var decision = reviewedPolicy.decide(question, candidates == null ? List.of() : candidates.stream()
                    .map(c -> new ReviewedFaqPolicy.Offer(c.faq().faqId(), c.faq().question(), c.faq().answer(),
                            c.faq().similarityScore(), c.registered(),
                            c.faq().cacheable() && c.faq().intent() == Intent.GENERAL)).toList());
            log.info("SEMANTIC_CACHE decision: attemptId={}, policy=reviewed, reason={}, faqId={}, score={}, gap={}",
                    attemptId, decision.reason(), decision.faqId(), decision.score(), decision.gap());
            return decision.faqId() != null
                    ? candidates.stream().filter(c -> c.faq().faqId().equals(decision.faqId()))
                        .map(SemanticFaqCandidateResponseDto::faq).findFirst() : Optional.empty();
        }
        String reason = "no_candidates";
        double score = Double.NaN;
        double gap = Double.NaN;
        FaqSearchResponseDto selected = null;
        if (candidates != null && !candidates.isEmpty()) {
            var first = candidates.getFirst();
            score = first.faq().similarityScore();
            // No competing FAQ means insufficient evidence for the margin decision.
            if (candidates.size() > 1) {
                gap = score - candidates.get(1).faq().similarityScore();
            }
            if (!first.registered() || !first.faq().cacheable() || first.faq().intent() != Intent.GENERAL) {
                reason = "not_approved_or_registered";
            } else if (!Double.isFinite(score) || score < properties.getMinSimilarity()) {
                reason = "below_threshold";
            } else if (!Double.isFinite(gap) || gap < properties.getMinMargin()) {
                reason = "ambiguous";
            } else {
                reason = "accepted";
                selected = first.faq();
            }
        }
        log.info("SEMANTIC_CACHE decision: attemptId={}, reason={}, faqId={}, score={}, gap={}",
                attemptId, reason, selected == null ? null : selected.faqId(), score, gap);
        return Optional.ofNullable(selected);
    }
}
