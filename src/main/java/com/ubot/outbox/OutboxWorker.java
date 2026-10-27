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

    // 이 횟수를 다 쓴 이벤트는 다시 처리하지 않고 테이블에 남음(적체 지표의 dead)
    public static final int MAX_RETRIES = 5;
    static final int BATCH_SIZE = 20;
    static final long RETRY_BASE_DELAY_SECONDS = 10;
    static final long EXTERNAL_HANDLER_LEASE_SECONDS = 300;

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
                ProcessResult result = transactionTemplate.execute(status -> {
                    Optional<OutboxEvent> next = outboxRepository.lockNext(MAX_RETRIES);
                    if (next.isEmpty()) return ProcessResult.empty();

                    OutboxEvent event = next.get();
                    locked[0] = event;
                    OutboxEventHandler handler = handlers.get(event.type());
                    if (handler == null) {
                        throw new IllegalStateException("처리할 수 없는 대기열 작업입니다: " + event.type());
                    }
                    if (handler.runsOutsideTransaction()) {
                        outboxRepository.lease(event.id(), EXTERNAL_HANDLER_LEASE_SECONDS);
                        return ProcessResult.claimed(event, handler);
                    }
                    handler.handle(event.payload());
                    outboxRepository.delete(event.id());
                    return ProcessResult.completed();
                });
                if (result == null || !result.found()) {
                    return;
                }
                if (result.claimed() != null) {
                    result.claimed().handler().handle(result.claimed().event().payload());
                    transactionTemplate.executeWithoutResult(status ->
                            outboxRepository.delete(result.claimed().event().id()));
                }
            } catch (RuntimeException exception) {
                if (locked[0] == null) {
                    log.warn("대기열을 읽지 못했습니다", exception);
                    return;
                }
                int retryCount = outboxRepository.increaseRetryCount(locked[0].id(), RETRY_BASE_DELAY_SECONDS);
                if (retryCount >= MAX_RETRIES) {
                    log.error("대기열 작업이 {}번 실패해 더 처리하지 않습니다: 대기열ID={}, 작업={}",
                            retryCount, locked[0].id(), locked[0].type(), exception);
                } else {
                    log.warn("대기열 작업에 실패해 나중에 다시 시도합니다: 대기열ID={}, 작업={}, 실패횟수={}",
                            locked[0].id(), locked[0].type(), retryCount, exception);
                }
                return;
            }
        }
    }

    private record ClaimedEvent(OutboxEvent event, OutboxEventHandler handler) {
    }

    private record ProcessResult(boolean found, ClaimedEvent claimed) {
        private static ProcessResult empty() {
            return new ProcessResult(false, null);
        }

        private static ProcessResult completed() {
            return new ProcessResult(true, null);
        }

        private static ProcessResult claimed(OutboxEvent event, OutboxEventHandler handler) {
            return new ProcessResult(true, new ClaimedEvent(event, handler));
        }
    }
}
