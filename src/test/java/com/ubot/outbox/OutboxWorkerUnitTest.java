package com.ubot.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.outbox.OutboxEventRepository.OutboxEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("대기열 워커")
class OutboxWorkerUnitTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    {
        when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    }

    @Test
    @DisplayName("lease로 선점한 작업을 트랜잭션 밖 handler가 실패시키면 재시도 횟수를 늘린다")
    void increasesRetryCountWhenLeasedHandlerFails() {
        JsonNode payload = JSON_MAPPER.readTree("{\"questionLogId\":1}");
        OutboxEvent event = new OutboxEvent(10L, OutboxEventType.SURGING_QUESTION, payload);
        when(outboxRepository.lockNext(OutboxWorker.MAX_RETRIES)).thenReturn(Optional.of(event));

        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        when(handler.type()).thenReturn(OutboxEventType.SURGING_QUESTION);
        when(handler.runsOutsideTransaction()).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("handler failed"))
                .when(handler).handle(payload);

        OutboxWorker worker = new OutboxWorker(outboxRepository, List.of(handler), transactionManager);
        worker.processPending();

        verify(outboxRepository).lease(eq(10L), eq(OutboxWorker.EXTERNAL_HANDLER_LEASE_SECONDS));
        verify(outboxRepository).increaseRetryCount(10L, OutboxWorker.RETRY_BASE_DELAY_SECONDS);
        verify(outboxRepository, never()).delete(10L);
    }

    @Test
    @DisplayName("lease로 선점한 작업을 handler가 성공시키면 대기열에서 지운다")
    void deletesLeasedEventWhenHandlerSucceeds() {
        JsonNode payload = JSON_MAPPER.readTree("{\"questionLogId\":1}");
        OutboxEvent event = new OutboxEvent(11L, OutboxEventType.SURGING_QUESTION, payload);
        when(outboxRepository.lockNext(OutboxWorker.MAX_RETRIES))
                .thenReturn(Optional.of(event), Optional.empty());

        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        when(handler.type()).thenReturn(OutboxEventType.SURGING_QUESTION);
        when(handler.runsOutsideTransaction()).thenReturn(true);

        OutboxWorker worker = new OutboxWorker(outboxRepository, List.of(handler), transactionManager);
        worker.processPending();

        verify(outboxRepository).lease(eq(11L), eq(OutboxWorker.EXTERNAL_HANDLER_LEASE_SECONDS));
        verify(outboxRepository).delete(11L);
        verify(outboxRepository, never()).increaseRetryCount(anyLong(), anyLong());
    }
}
