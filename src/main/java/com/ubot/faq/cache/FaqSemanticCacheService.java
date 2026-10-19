package com.ubot.faq.cache;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.ubot.faq.cache.FaqCacheDecision.Reason;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;

import lombok.RequiredArgsConstructor;

/**
 * 재정렬·유사도 필터 적용 전의 전체 FAQ 검색 순위로 공식 답변 반환 여부를 판단합니다.
 * cacheable FAQ만 추리거나, 리랭커 점수를 코사인 유사도로 취급하지 않습니다.
 * 점수는 단일 의도/답변 완전성을 보장하지 않으므로 운영 적용 전 별도 질문셋 검증이 필요합니다.
 */
@Service
@RequiredArgsConstructor
public class FaqSemanticCacheService {

    private final FaqCacheProperties properties;

    /** CHAT_TOP_K=1이어도 서로 다른 FAQ 두 건을 비교합니다. */
    public int minimumCandidateCount(Intent requestedIntent) {
        return properties.isEnabled() && !requiresContext(requestedIntent) ? 2 : 0;
    }

    public FaqCacheDecision evaluate(Intent requestedIntent, List<FaqSearchResponseDto> candidates) {
        if (!properties.isEnabled()) {
            return FaqCacheDecision.miss(Reason.DISABLED, null, null);
        }
        if (requiresContext(requestedIntent)) {
            return FaqCacheDecision.miss(Reason.CONTEXT_REQUIRED, null, null);
        }
        if (candidates == null || candidates.isEmpty()) {
            return FaqCacheDecision.miss(Reason.NO_CANDIDATES, null, null);
        }
        // 저장소 계약(FAQ별 한 건, 유사도 내림차순)이 깨지면 추측해서 승인하지 않습니다.
        if (!isValidRanking(candidates)) {
            return FaqCacheDecision.miss(Reason.INVALID_CANDIDATES, null, null);
        }

        FaqSearchResponseDto best = candidates.getFirst();
        double similarity = best.similarityScore();
        Double margin = candidates.size() < 2 ? null : similarity - candidates.get(1).similarityScore();
        if (!best.cacheable() || best.intent() != Intent.GENERAL) {
            return FaqCacheDecision.miss(Reason.NOT_CACHEABLE_GENERAL, similarity, margin);
        }
        if (similarity < properties.getMinSimilarity()) {
            return FaqCacheDecision.miss(Reason.BELOW_SIMILARITY, similarity, margin);
        }
        if (margin == null) {
            return FaqCacheDecision.miss(Reason.INSUFFICIENT_CANDIDATES, similarity, null);
        }
        if (margin < properties.getMinMargin()) {
            return FaqCacheDecision.miss(Reason.BELOW_MARGIN, similarity, margin);
        }
        if (!StringUtils.hasText(best.answer())) {
            return FaqCacheDecision.miss(Reason.INVALID_CANDIDATES, similarity, margin);
        }
        // 검색 시점의 승인 상태와 답변을 사용합니다. 추가 DB 조회나 RAG 후보 변경은 하지 않습니다.
        return FaqCacheDecision.hit(best, similarity, margin);
    }

    private boolean requiresContext(Intent requestedIntent) {
        return requestedIntent != null && requestedIntent != Intent.GENERAL;
    }

    private boolean isValidRanking(List<FaqSearchResponseDto> candidates) {
        Set<Long> faqIds = new HashSet<>();
        double previousScore = Double.POSITIVE_INFINITY;
        for (FaqSearchResponseDto candidate : candidates) {
            if (candidate == null || candidate.faqId() == null
                    || !Double.isFinite(candidate.similarityScore())
                    || candidate.similarityScore() < -1 || candidate.similarityScore() > 1
                    || candidate.similarityScore() > previousScore || !faqIds.add(candidate.faqId())) {
                return false;
            }
            previousScore = candidate.similarityScore();
        }
        return true;
    }
}
