package com.ubot.faq.cache;

import java.util.List;
import java.util.Optional;

import com.ubot.faq.dto.response.FaqSearchResponseDto;

/** HIT의 공식 답변과 MISS 후 RAG에 사용할 수 있는 후보를 구분합니다. */
public record FaqCacheDecision(
        Reason reason,
        Optional<FaqSearchResponseDto> faq,
        Double similarity,
        Double margin,
        List<FaqSearchResponseDto> candidatesForRag
) {
    public FaqCacheDecision {
        candidatesForRag = List.copyOf(candidatesForRag);
    }

    public enum Reason {
        HIT,
        DISABLED,
        CONTEXT_REQUIRED,
        NO_CANDIDATES,
        INVALID_CANDIDATES,
        NOT_CACHEABLE_GENERAL,
        BELOW_SIMILARITY,
        INSUFFICIENT_CANDIDATES,
        BELOW_MARGIN,
        FAQ_CHANGED,
        VALIDATION_FAILED
    }

    static FaqCacheDecision miss(Reason reason, Double similarity, Double margin,
            List<FaqSearchResponseDto> candidatesForRag) {
        return new FaqCacheDecision(reason, Optional.empty(), similarity, margin, candidatesForRag);
    }

    static FaqCacheDecision hit(FaqSearchResponseDto faq, double similarity, double margin) {
        return new FaqCacheDecision(Reason.HIT, Optional.of(faq), similarity, margin, List.of());
    }
}
