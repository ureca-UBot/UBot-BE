package com.ubot.unanswered.service;

import org.springframework.stereotype.Component;

import com.ubot.outbox.OutboxEventHandler;
import com.ubot.outbox.OutboxEventType;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.enums.UnansweredSource;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class UnansweredOutboxHandler implements OutboxEventHandler {

    private final UnansweredQuestionService unansweredQuestionService;

    @Override
    public OutboxEventType type() {
        return OutboxEventType.UNANSWERED_QUESTION;
    }

    @Override
    public void handle(JsonNode payload) {
        JsonNode bestFaqId = payload.get("bestFaqId");
        JsonNode bestSimilarity = payload.get("bestSimilarity");
        JsonNode source = payload.get("source");
        unansweredQuestionService.createUnansweredQuestion(
                payload.get("attemptId").asLong(),
                payload.get("subQuestionIndex").asInt(),
                payload.get("subQuestion").asString(),
                payload.get("userText").asString(),
                UnansweredReason.valueOf(payload.get("reason").asString()),
                bestFaqId == null || bestFaqId.isNull() ? null : bestFaqId.asLong(),
                bestSimilarity == null || bestSimilarity.isNull() ? null : bestSimilarity.asDouble(),
                source == null || source.isNull() ? UnansweredSource.RETRIEVAL : UnansweredSource.valueOf(source.asString())
        );
    }
}
