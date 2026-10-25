package com.ubot.outbox;

import tools.jackson.databind.JsonNode;

public interface OutboxEventHandler {

    OutboxEventType type();

    void handle(JsonNode payload);

    /**
     * 외부 HTTP 호출처럼 오래 걸릴 수 있는 handler는 이벤트를 먼저 lease로 선점·커밋한 뒤
     * 트랜잭션 밖에서 실행한다.
     */
    default boolean runsOutsideTransaction() {
        return false;
    }
}
