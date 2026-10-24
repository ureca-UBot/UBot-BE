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
public class SurgingAlertLabelOutboxHandler implements OutboxEventHandler {

    private final TelemapAlertLabelService telemapAlertLabelService;

    @Override
    public OutboxEventType type() {
        return OutboxEventType.SURGING_ALERT_LABEL;
    }

    @Override
    public boolean runsOutsideTransaction() {
        return true;
    }

    @Override
    public void handle(JsonNode payload) {
        JsonNode alertId = payload.get("alertId");
        if (alertId == null || alertId.isNull()) {
            throw new TelemapException(TelemapErrorCode.INVALID_OUTBOX_EVENT);
        }
        telemapAlertLabelService.labelAndNotify(alertId.asLong());
    }
}
