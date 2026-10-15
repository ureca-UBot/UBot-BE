package com.ubot.faq.cache;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.ubot.faq.cache.FaqCacheDecision.Reason;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqVectorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 재정렬·유사도 필터 적용 전의 전체 FAQ 검색 순위로 공식 답변 반환 여부를 판단합니다.
 * cacheable FAQ만 추리거나, 리랭커 점수를 코사인 유사도로 취급하지 않습니다.
 * 점수는 단일 의도/답변 완전성을 보장하지 않으므로 운영 적용 전 별도 질문셋 검증이 필요합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqSemanticCacheService {

    private final FaqCacheProperties properties;
    private final FaqVectorRepository faqVectorRepository;

    /** CHAT_TOP_K=1이어도 서로 다른 FAQ 두 건을 비교합니다. */
    public int minimumCandidateCount(Intent requestedIntent) {
        return properties.isEnabled() && !requiresContext(requestedIntent) ? 2 : 0;
    }

    public FaqCacheDecision evaluate(Intent requestedIntent, List<FaqSearchResponseDto> candidates) {
        List<FaqSearchResponseDto> originalCandidates = candidates == null ? List.of() : candidates;
        if (!properties.isEnabled()) {
            return FaqCacheDecision.miss(Reason.DISABLED, null, null, originalCandidates);
        }
        if (requiresContext(requestedIntent)) {
            return FaqCacheDecision.miss(Reason.CONTEXT_REQUIRED, null, null, originalCandidates);
        }
        if (candidates == null || candidates.isEmpty()) {
            return FaqCacheDecision.miss(Reason.NO_CANDIDATES, null, null, List.of());
        }
        // 저장소 계약(FAQ별 한 건, 유사도 내림차순)이 깨지면 추측해서 승인하지 않습니다.
        if (!isValidRanking(candidates)) {
            return FaqCacheDecision.miss(Reason.INVALID_CANDIDATES, null, null, List.of());
        }

        FaqSearchResponseDto best = candidates.getFirst();
        double similarity = best.similarityScore();
        Double margin = candidates.size() < 2 ? null : similarity - candidates.get(1).similarityScore();
        if (!best.cacheable() || best.intent() != Intent.GENERAL) {
            return FaqCacheDecision.miss(Reason.NOT_CACHEABLE_GENERAL, similarity, margin, candidates);
        }
        if (similarity < properties.getMinSimilarity()) {
            return FaqCacheDecision.miss(Reason.BELOW_SIMILARITY, similarity, margin, candidates);
        }
        if (margin == null) {
            return FaqCacheDecision.miss(Reason.INSUFFICIENT_CANDIDATES, similarity, null, candidates);
        }
        if (margin < properties.getMinMargin()) {
            return FaqCacheDecision.miss(Reason.BELOW_MARGIN, similarity, margin, candidates);
        }
        if (best.variantId() == null || best.faqVersion() == null) {
            return FaqCacheDecision.miss(Reason.FAQ_CHANGED, similarity, margin,
                    candidates.stream().filter(candidate -> !candidate.faqId().equals(best.faqId())).toList());
        }

        List<FaqSearchResponseDto> unchangedCandidates;
        try {
            unchangedCandidates = faqVectorRepository.findUnchangedCandidates(candidates);
        } catch (DataAccessException exception) {
            // 추가 검증 조회만 실패한 경우입니다. 이미 성공한 검색 결과로 기존 RAG를 진행합니다.
            log.warn("FAQ 캐시 재검증에 실패해 기존 답변 처리를 진행합니다: FAQ ID={}", best.faqId(), exception);
            return FaqCacheDecision.miss(Reason.VALIDATION_FAILED, similarity, margin, candidates);
        }
        Optional<FaqSearchResponseDto> currentBest = unchangedCandidates.stream()
                .filter(candidate -> candidate.faqId().equals(best.faqId())).findFirst();
        if (currentBest.isEmpty()) {
            // 내용 변경·삭제된 후보는 캐시뿐 아니라 뒤의 RAG에서도 사용하지 않습니다.
            return FaqCacheDecision.miss(Reason.FAQ_CHANGED, similarity, margin, unchangedCandidates);
        }
        FaqSearchResponseDto current = currentBest.get();
        if (!current.cacheable()) {
            return FaqCacheDecision.miss(Reason.NOT_CACHEABLE_GENERAL, similarity, margin, unchangedCandidates);
        }
        if (!Objects.equals(current.faqVersion(), best.faqVersion())) {
            // 내용이 같아 RAG에 재사용할 수 있어도 검색 당시 버전이 바뀌면 HIT하지 않습니다.
            return FaqCacheDecision.miss(Reason.FAQ_CHANGED, similarity, margin, unchangedCandidates);
        }
        if (!StringUtils.hasText(current.answer())) {
            return FaqCacheDecision.miss(Reason.FAQ_CHANGED, similarity, margin,
                    unchangedCandidates.stream().filter(candidate -> !candidate.faqId().equals(best.faqId())).toList());
        }
        return FaqCacheDecision.hit(current, similarity, margin);
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
