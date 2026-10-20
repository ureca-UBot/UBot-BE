package com.ubot.unanswered.dto;

import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.enums.UnansweredSource;

public record UnansweredCandidate(
        int subQuestionIndex,
        String subQuestion,
        String userText,
        UnansweredReason reason,
        Long bestFaqId,
        Double bestSimilarity,
        UnansweredSource source
) {
}
