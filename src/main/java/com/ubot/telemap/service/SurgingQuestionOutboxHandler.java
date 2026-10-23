package com.ubot.telemap.service;

import org.springframework.stereotype.Component;

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
        telemapSurgingQuestionService.registerQuestion(questionLogId.asLong());
    }
}
