package com.ubot.faq.retrieval.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Reranker 호출 보호 장치")
class RerankCallGuardTest {

    private static final Supplier<String> FAILING = () -> {
        throw new RerankException("제한 시간 초과");
    };

    private final AtomicLong now = new AtomicLong(1_000);
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("한도 안에서는 호출 결과를 그대로 돌려준다")
    void passesThrough() {
        assertThat(guard(2, 5).call(() -> "결과")).isEqualTo("결과");
    }

    @Test
    @DisplayName("동시 호출이 한도에 차면 기다리지 않고 건너뛰고, 자리가 나면 다시 호출한다")
    void skipsWhenConcurrencyLimitReached() throws InterruptedException {
        RerankCallGuard guard = guard(1, 5);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> guard.call(() -> {
            entered.countDown();
            await(release);
            return "첫 호출";
        }));
        holder.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> guard.call(counting("둘째 호출"))).isInstanceOf(RerankSkippedException.class);
        assertThat(calls).hasValue(0);

        release.countDown();
        holder.join(2_000);
        assertThat(guard.call(counting("셋째 호출"))).isEqualTo("셋째 호출");
    }

    @Test
    @DisplayName("연속 실패가 한도에 이르면 정해 둔 시간 동안 호출하지 않는다")
    void skipsAfterConsecutiveFailures() {
        RerankCallGuard guard = guard(4, 3);
        failTimes(guard, 3);

        assertThatThrownBy(() -> guard.call(counting("호출"))).isInstanceOf(RerankSkippedException.class);
        now.addAndGet(Duration.ofSeconds(9).toNanos());
        assertThatThrownBy(() -> guard.call(counting("호출"))).isInstanceOf(RerankSkippedException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("실패 사이에 성공이 있으면 연속 실패로 세지 않는다")
    void successResetsFailureCount() {
        RerankCallGuard guard = guard(4, 3);
        failTimes(guard, 2);
        guard.call(() -> "성공");
        failTimes(guard, 2);

        assertThat(guard.call(counting("호출"))).isEqualTo("호출");
    }

    @Test
    @DisplayName("건너뛰는 시간이 지나면 한 건을 보내 보고, 성공하면 호출을 재개한다")
    void resumesAfterSuccessfulProbe() {
        RerankCallGuard guard = guard(4, 3);
        failTimes(guard, 3);
        now.addAndGet(Duration.ofSeconds(10).toNanos());

        assertThat(guard.call(counting("확인"))).isEqualTo("확인");
        assertThat(guard.call(counting("다음"))).isEqualTo("다음");
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("확인용 호출이 또 실패하면 다시 같은 시간만큼 건너뛴다")
    void failedProbeKeepsSkipping() {
        RerankCallGuard guard = guard(4, 3);
        failTimes(guard, 3);
        now.addAndGet(Duration.ofSeconds(10).toNanos());

        assertThatThrownBy(() -> guard.call(FAILING)).isInstanceOf(RerankException.class)
                .isNotInstanceOf(RerankSkippedException.class);
        assertThatThrownBy(() -> guard.call(counting("호출"))).isInstanceOf(RerankSkippedException.class);
        now.addAndGet(Duration.ofSeconds(10).toNanos());
        assertThat(guard.call(counting("확인"))).isEqualTo("확인");
    }

    @Test
    @DisplayName("확인용 호출이 진행 중일 때 들어온 다른 요청은 건너뛴다")
    void onlyOneProbeAtATime() throws InterruptedException {
        RerankCallGuard guard = guard(4, 3);
        failTimes(guard, 3);
        now.addAndGet(Duration.ofSeconds(10).toNanos());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread probe = new Thread(() -> guard.call(() -> {
            entered.countDown();
            await(release);
            return "확인";
        }));
        probe.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> guard.call(counting("호출"))).isInstanceOf(RerankSkippedException.class);

        release.countDown();
        probe.join(2_000);
        assertThat(guard.call(counting("재개"))).isEqualTo("재개");
    }

    @Test
    @DisplayName("작업이 취소돼 중단된 호출은 실패로 세지 않는다")
    void interruptedCallIsNotCountedAsFailure() {
        RerankCallGuard guard = guard(4, 1);

        assertThatThrownBy(() -> guard.call(() -> {
            Thread.currentThread().interrupt();
            throw new RerankException("응답을 기다리다 중단되었습니다.");
        })).isInstanceOf(RerankException.class);
        Thread.interrupted();

        assertThat(guard.call(counting("호출"))).isEqualTo("호출");
    }

    private RerankCallGuard guard(int maxConcurrent, int failureThreshold) {
        return new RerankCallGuard(maxConcurrent, failureThreshold, Duration.ofSeconds(10), now::get);
    }

    private void failTimes(RerankCallGuard guard, int times) {
        for (int i = 0; i < times; i++) {
            assertThatThrownBy(() -> guard.call(FAILING)).isInstanceOf(RerankException.class);
        }
    }

    private Supplier<String> counting(String result) {
        return () -> {
            calls.incrementAndGet();
            return result;
        };
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
