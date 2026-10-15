package com.ubot.faq.cache;

import java.util.Optional;

import com.ubot.faq.dto.response.FaqSearchResponseDto;

/** HIT에만 공식 답변이 있습니다. 점수와 차이는 미달 사유를 확인하는 데 사용합니다. */
public record FaqCacheDecision(
        Reason reason,
        Optional<FaqSearchResponseDto> faq,
        Double similarity,
        Double margin
) {
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

    static FaqCacheDecision miss(Reason reason, Double similarity, Double margin) {
        return new FaqCacheDecision(reason, Optional.empty(), similarity, margin);
    }

    static FaqCacheDecision hit(FaqSearchResponseDto faq, double similarity, double margin) {
        return new FaqCacheDecision(Reason.HIT, Optional.of(faq), similarity, margin);
    }
}
