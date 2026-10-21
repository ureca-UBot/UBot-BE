package com.ubot.outbox;

import tools.jackson.databind.JsonNode;

public interface OutboxEventHandler {

    OutboxEventType type();

    void handle(JsonNode payload);
}
