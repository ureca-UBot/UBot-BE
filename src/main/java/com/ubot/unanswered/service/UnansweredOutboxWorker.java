package com.ubot.unanswered.service;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ubot.global.config.AppRole;
import com.ubot.unanswered.repository.UnansweredOutboxRepository;
import com.ubot.unanswered.repository.UnansweredOutboxRepository.PendingUnanswered;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnExpression(AppRole.RUNS_WORKER)
public class UnansweredOutboxWorker {

    static final int MAX_RETRIES = 5;
    static final int BATCH_SIZE = 20;

    private final UnansweredOutboxRepository outboxRepository;
    private final UnansweredQuestionService unansweredQuestionService;
    private final TransactionTemplate transactionTemplate;

    public UnansweredOutboxWorker(
            UnansweredOutboxRepository outboxRepository,
            UnansweredQuestionService unansweredQuestionService,
            PlatformTransactionManager transactionManager
    ) {
        this.outboxRepository = outboxRepository;
        this.unansweredQuestionService = unansweredQuestionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${UNANSWERED_OUTBOX_POLL_DELAY_MS:2000}")
    public void processPending() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            Long[] lockedId = {null};
            try {
                Optional<PendingUnanswered> processed = transactionTemplate.execute(status -> {
                    Optional<PendingUnanswered> next = outboxRepository.lockNext(MAX_RETRIES);
                    next.ifPresent(pending -> {
                        lockedId[0] = pending.id();
                        unansweredQuestionService.createUnansweredQuestion(
                                pending.attemptId(),
                                pending.subQuestionIndex(),
                                pending.subQuestion(),
                                pending.userText(),
                                pending.reason(),
                                pending.bestFaqId(),
                                pending.bestSimilarity()
                        );
                        outboxRepository.delete(pending.id());
                    });
                    return next;
                });
                if (processed == null || processed.isEmpty()) {
                    return;
                }
            } catch (RuntimeException exception) {
                if (lockedId[0] == null) {
                    log.warn("미응답 질문 대기열을 읽지 못했습니다", exception);
                    return;
                }
                outboxRepository.increaseRetryCount(lockedId[0]);
                log.warn("미응답 질문 저장에 실패해 다음에 다시 시도합니다: 대기열ID={}", lockedId[0], exception);
                return;
            }
        }
    }
}
