package com.ubot.unanswered.dto;

import com.ubot.unanswered.enums.UnansweredReason;
import java.util.List;

/** 미응답 후보와 선택적인 검색 벡터를 Worker의 Outbox 등록 단계로 전달합니다. */
public record UnansweredCandidate(
        int subQuestionIndex,
        String subQuestion,
        String userText,
        UnansweredReason reason,
        Long bestFaqId,
        Double bestSimilarity,
        Long embeddingProfileId,
        List<Float> queryEmbedding
) {
    public UnansweredCandidate {
        queryEmbedding = queryEmbedding == null ? null : List.copyOf(queryEmbedding);
    }

    public UnansweredCandidate(int subQuestionIndex, String subQuestion, String userText, UnansweredReason reason,
            Long bestFaqId, Double bestSimilarity) {
        this(subQuestionIndex, subQuestion, userText, reason, bestFaqId, bestSimilarity, null, null);
    }
}
