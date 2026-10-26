package com.ubot.unanswered.service;

import org.springframework.stereotype.Component;

import com.ubot.outbox.OutboxEventHandler;
import com.ubot.outbox.OutboxEventType;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.service.RejectedQuestionDuplicateChecker.QuestionEmbedding;
import com.pgvector.PGvector;

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
        JsonNode embedding = payload.get("queryEmbedding");
        JsonNode profileId = payload.get("embeddingProfileId");
        QuestionEmbedding reusable = null;
        if (embedding != null && !embedding.isNull() && profileId != null && !profileId.isNull()) {
            if (!embedding.isArray()) throw new IllegalArgumentException("미응답 검색 벡터는 배열이어야 합니다.");
            float[] values = new float[embedding.size()];
            for (int i = 0; i < values.length; i++) values[i] = (float) embedding.get(i).asDouble();
            reusable = new QuestionEmbedding(payload.get("subQuestion").asString(), profileId.asLong(), new PGvector(values));
        }
        unansweredQuestionService.createUnansweredQuestion(
                payload.get("attemptId").asLong(),
                payload.get("subQuestionIndex").asInt(),
                payload.get("subQuestion").asString(),
                payload.get("userText").asString(),
                UnansweredReason.valueOf(payload.get("reason").asString()),
                bestFaqId == null || bestFaqId.isNull() ? null : bestFaqId.asLong(),
                bestSimilarity == null || bestSimilarity.isNull() ? null : bestSimilarity.asDouble(),
                reusable
        );
    }
}
