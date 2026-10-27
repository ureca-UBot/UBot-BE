package com.ubot.faq.cache;

import java.util.Optional;

import com.ubot.faq.dto.response.FaqSearchResponseDto;

/** 검색 결과를 기준으로 판정한 사유와 HIT의 공식 답변입니다. RAG 후보는 변경하지 않습니다. */
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
        BELOW_MARGIN
    }

    static FaqCacheDecision miss(Reason reason, Double similarity, Double margin) {
        return new FaqCacheDecision(reason, Optional.empty(), similarity, margin);
    }

    static FaqCacheDecision hit(FaqSearchResponseDto faq, double similarity, double margin) {
        return new FaqCacheDecision(Reason.HIT, Optional.of(faq), similarity, margin);
    }
}
