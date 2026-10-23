package com.ubot.telemap.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.pgvector.PGvector;
import com.ubot.outbox.OutboxEventHandler;
import com.ubot.outbox.OutboxEventType;
import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class SurgingQuestionOutboxHandler implements OutboxEventHandler {

    private final TelemapSurgingQuestionService telemapSurgingQuestionService;

    @Override
    public OutboxEventType type() {
        return OutboxEventType.SURGING_QUESTION;
    }

    @Override
    public void handle(JsonNode payload) {
        JsonNode questionLogId = payload.get("questionLogId");
        if (questionLogId == null || questionLogId.isNull()) {
            throw new TelemapException(TelemapErrorCode.INVALID_OUTBOX_EVENT);
        }
        JsonNode profileId = payload.get("embeddingProfileId");
        JsonNode embedding = payload.get("embedding");
        if (profileId == null || !profileId.isIntegralNumber() || profileId.asLong() <= 0
                || embedding == null || !embedding.isArray() || embedding.isEmpty()) {
            throw new TelemapException(TelemapErrorCode.INVALID_OUTBOX_EVENT);
        }
        List<Float> values = new ArrayList<>();
        for (JsonNode value : embedding) {
            if (!value.isNumber()) throw new TelemapException(TelemapErrorCode.INVALID_OUTBOX_EVENT);
            values.add((float) value.asDouble());
        }
        float[] vector = new float[values.size()];
        for (int index = 0; index < values.size(); index++) vector[index] = values.get(index);
        telemapSurgingQuestionService.registerQuestion(questionLogId.asLong(), profileId.asLong(), new PGvector(vector));
    }
}
