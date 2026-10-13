package com.ubot.faq.retrieval.rerank;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * 요청이 몰리거나 Reranker 서버가 응답하지 못할 때 호출을 줄여 서버와 채팅 응답 시간을 보호합니다.
 *
 * <ul>
 *   <li>동시에 나가는 호출 수를 제한합니다. 자리가 없으면 기다리지 않고 건너뜁니다.</li>
 *   <li>연속으로 실패하면 일정 시간 호출하지 않습니다. 시간이 지나면 한 건만 보내 보고, 성공하면 다시 엽니다.</li>
 * </ul>
 *
 * 건너뛸 때는 {@link RerankSkippedException}을 던지고, 호출한 쪽은 벡터 검색 결과로 답합니다.
 */
@Slf4j
public class RerankCallGuard {

    // 0이면 닫힌 상태(정상 호출)입니다. 0이 아니면 그 시각(nanoTime)까지 호출을 건너뜁니다.
    private static final long CLOSED = 0L;

    private final Semaphore permits;
    private final int failureThreshold;
    private final long cooldownNanos;
    private final LongSupplier nanoClock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong skipUntilNanos = new AtomicLong(CLOSED);

    public RerankCallGuard(int maxConcurrent, int failureThreshold, Duration failureCooldown) {
        this(maxConcurrent, failureThreshold, failureCooldown, System::nanoTime);
    }

    RerankCallGuard(int maxConcurrent, int failureThreshold, Duration failureCooldown, LongSupplier nanoClock) {
        this.permits = new Semaphore(maxConcurrent);
        this.failureThreshold = failureThreshold;
        this.cooldownNanos = failureCooldown.toNanos();
        this.nanoClock = nanoClock;
    }

    /**
     * 보호 장치를 거쳐 Reranker를 호출합니다.
     *
     * @throws RerankSkippedException 동시 호출 한도를 넘었거나 연속 실패로 건너뛰는 중인 경우
     * @throws RerankException call이 던진 Reranker 실패
     */
    public <T> T call(Supplier<T> call) {
        if (!permits.tryAcquire()) {
            throw new RerankSkippedException("Reranker 동시 호출 한도를 넘어 건너뜁니다.");
        }
        try {
            skipIfFailingRepeatedly();
            T result = call.get();
            onSuccess();
            return result;
        } catch (RerankSkippedException exception) {
            throw exception;
        } catch (RerankException exception) {
            // 작업이 취소돼 중단된 호출은 서버 상태와 관계가 없으므로 실패로 세지 않습니다.
            if (!Thread.currentThread().isInterrupted()) {
                onFailure();
            }
            throw exception;
        } finally {
            permits.release();
        }
    }

    /** 연속 실패로 건너뛰는 중이면 예외를 던집니다. 건너뛰는 시간이 끝난 뒤의 첫 요청은 확인용으로 통과시킵니다. */
    private void skipIfFailingRepeatedly() {
        long skipUntil = skipUntilNanos.get();
        if (skipUntil == CLOSED) {
            return;
        }
        long now = nanoClock.getAsLong();
        // 건너뛰는 시간이 끝났으면 한 요청만 확인용으로 보내고, 나머지는 그 결과가 나올 때까지 계속 건너뜁니다.
        if (now - skipUntil >= 0 && skipUntilNanos.compareAndSet(skipUntil, nextSkipUntil(now))) {
            return;
        }
        throw new RerankSkippedException("Reranker가 연속으로 실패해 잠시 건너뜁니다.");
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        if (skipUntilNanos.getAndSet(CLOSED) != CLOSED) {
            log.info("Reranker가 다시 응답해 호출을 재개합니다.");
        }
    }

    private void onFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures < failureThreshold) {
            return;
        }
        long previous = skipUntilNanos.getAndSet(nextSkipUntil(nanoClock.getAsLong()));
        if (previous == CLOSED) {
            log.warn("Reranker가 연속 {}번 실패해 {}초 동안 호출을 건너뜁니다.", failures, cooldownNanos / 1_000_000_000.0);
        }
    }

    private long nextSkipUntil(long now) {
        long until = now + cooldownNanos;
        // 0은 닫힌 상태를 뜻하므로 피합니다.
        return until == CLOSED ? 1L : until;
    }
}
