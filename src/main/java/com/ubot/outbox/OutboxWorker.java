package com.ubot.outbox;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ubot.global.config.AppRole;
import com.ubot.outbox.OutboxEventRepository.OutboxEvent;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnExpression(AppRole.RUNS_WORKER)
public class OutboxWorker {

    static final int MAX_RETRIES = 5;
    static final int BATCH_SIZE = 20;

    private final OutboxEventRepository outboxRepository;
    private final Map<OutboxEventType, OutboxEventHandler> handlers = new EnumMap<>(OutboxEventType.class);
    private final TransactionTemplate transactionTemplate;

    public OutboxWorker(
            OutboxEventRepository outboxRepository,
            List<OutboxEventHandler> handlers,
            PlatformTransactionManager transactionManager
    ) {
        this.outboxRepository = outboxRepository;
        handlers.forEach(handler -> this.handlers.put(handler.type(), handler));
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${OUTBOX_POLL_DELAY_MS:2000}")
    public void processPending() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            OutboxEvent[] locked = {null};
            try {
                Optional<OutboxEvent> processed = transactionTemplate.execute(status -> {
                    Optional<OutboxEvent> next = outboxRepository.lockNext(MAX_RETRIES);
                    next.ifPresent(event -> {
                        locked[0] = event;
                        OutboxEventHandler handler = handlers.get(event.type());
                        if (handler == null) {
                            throw new IllegalStateException("처리할 수 없는 대기열 작업입니다: " + event.type());
                        }
                        handler.handle(event.payload());
                        outboxRepository.delete(event.id());
                    });
                    return next;
                });
                if (processed == null || processed.isEmpty()) {
                    return;
                }
            } catch (RuntimeException exception) {
                if (locked[0] == null) {
                    log.warn("대기열을 읽지 못했습니다", exception);
                    return;
                }
                outboxRepository.increaseRetryCount(locked[0].id());
                log.warn("대기열 작업에 실패해 다음에 다시 시도합니다: 대기열ID={}, 작업={}",
                        locked[0].id(), locked[0].type(), exception);
                return;
            }
        }
    }
}
