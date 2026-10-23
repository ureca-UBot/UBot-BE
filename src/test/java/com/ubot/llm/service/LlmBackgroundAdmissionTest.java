package com.ubot.llm.service;

import static org.assertj.core.api.Assertions.*;

import com.ubot.ai.progress.AnswerProgress;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 모델 통신 없이 실제 LlmService의 동시 호출·대기·반납을 검사합니다. */
class LlmBackgroundAdmissionTest {

	static LlmRequestDto request(String name) {
		return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, name)));
	}

	static class Calls implements LlmClient, AutoCloseable {
		final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>(), started = new ConcurrentHashMap<>();
		final List<String> names = new CopyOnWriteArrayList<>();
		final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), background = new AtomicInteger(),
				backgroundPeak = new AtomicInteger();

		void block(String name) {
			gates.put(name, new CountDownLatch(1));
		}
		void awaitStarted(String name) throws InterruptedException {
			assertThat(started.computeIfAbsent(name, ignored -> new CountDownLatch(1)).await(5, TimeUnit.SECONDS))
					.isTrue();
		}
		void release(String name) {
			gates.get(name).countDown();
		}
		@Override
		public LlmResponseDto generateAnswer(LlmRequestDto request) {
			String name = request.messages().getLast().content();
			names.add(name);
			peak.accumulateAndGet(active.incrementAndGet(), Math::max);
			boolean review = name.startsWith("review-");
			if (review)
				backgroundPeak.accumulateAndGet(background.incrementAndGet(), Math::max);
			started.computeIfAbsent(name, ignored -> new CountDownLatch(1)).countDown();
			try {
				var gate = gates.get(name);
				if (gate != null && !gate.await(10, TimeUnit.SECONDS))
					throw new AssertionError("synthetic model release timeout: " + name);
				return new LlmResponseDto(name);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CancellationException("synthetic model interrupted");
			} finally {
				active.decrementAndGet();
				if (review)
					background.decrementAndGet();
			}
		}
		@Override
		public String getModelName() {
			return "synthetic-background-test";
		}
		@Override
		public void close() {
			gates.values().forEach(CountDownLatch::countDown);
		}
	}

	@Test
	void backgroundDoesNotWaitOrUseLastForegroundSlot() throws Exception {
		try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var calls = new Calls()) {
			var limited = new LlmService(calls, 2);
			calls.block("chat-1");
			calls.block("chat-2");
			var first = executor.submit(() -> limited.generateAnswer(request("chat-1")));
			calls.awaitStarted("chat-1");
			var skipped = executor.submit(() -> limited.tryGenerateBackgroundJudgment(request("review-1")));
			assertThat(skipped.get(2, TimeUnit.SECONDS)).isEmpty();
			assertThat(calls.names).doesNotContain("review-1");
			var second = executor.submit(() -> limited.generateJudgment(request("chat-2")));
			calls.awaitStarted("chat-2");
			assertThat(calls.peak.get()).isEqualTo(2);
			calls.close();
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
			assertThat(limited.tryGenerateBackgroundJudgment(request("review-1"))).isPresent();
		}
	}

	@Test
	void oneBackgroundSharesExistingLimitWithThreeForegroundCalls() throws Exception {
		try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var calls = new Calls()) {
			var limited = new LlmService(calls, 4);
			calls.block("review-1");
			var review = executor.submit(() -> limited.tryGenerateBackgroundJudgment(request("review-1")));
			calls.awaitStarted("review-1");
			var foreground = new ArrayList<Future<LlmResponseDto>>();
			for (String name : List.of("chat-1", "chat-2", "chat-3")) {
				calls.block(name);
				foreground.add(executor.submit(() -> limited.generateAnswer(request(name))));
				calls.awaitStarted(name);
			}
			assertThat(calls.active.get()).isEqualTo(4);
			assertThat(calls.backgroundPeak.get()).isEqualTo(1);
			assertThat(limited.tryGenerateBackgroundJudgment(request("review-2"))).isEmpty();
			calls.release("review-1");
			assertThat(review.get(5, TimeUnit.SECONDS)).isPresent();
			// Three chats still hold three slots: a second analysis must leave the remaining slot free.
			assertThat(limited.tryGenerateBackgroundJudgment(request("review-2"))).isEmpty();
			assertThat(calls.names).doesNotContain("review-2");
			calls.release("chat-1");
			foreground.getFirst().get(5, TimeUnit.SECONDS);
			assertThat(limited.tryGenerateBackgroundJudgment(request("review-2"))).isPresent();
			calls.close();
			for (var f : foreground)
				f.get(5, TimeUnit.SECONDS);
			assertThat(calls.peak.get()).isEqualTo(4);
			assertThat(calls.backgroundPeak.get()).isEqualTo(1);
		}
	}

	@Test
	void singleSlotAllowsBackgroundOnlyWhenIdle() throws Exception {
		try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var calls = new Calls()) {
			var limited = new LlmService(calls, 1);
			calls.block("chat-1");
			var chat = executor.submit(() -> limited.generateAnswer(request("chat-1")));
			calls.awaitStarted("chat-1");
			assertThat(limited.tryGenerateBackgroundJudgment(request("review-1"))).isEmpty();
			calls.release("chat-1");
			chat.get(5, TimeUnit.SECONDS);
			assertThat(limited.tryGenerateBackgroundJudgment(request("review-1"))).isPresent();
			assertThat(calls.peak.get()).isEqualTo(1);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"NULL", "RUNTIME", "LLM"})
	void modelFailureReleasesBothPermitsWithoutRetry(String failureKind) {
		var attempts = new AtomicInteger();
		RuntimeException failure = failureKind.equals("LLM")
				? new LlmException(LlmErrorCode.LLM_TIMEOUT)
				: new IllegalStateException("synthetic failure");
		LlmClient client = new LlmClient() {
			@Override
			public LlmResponseDto generateAnswer(LlmRequestDto request) {
				if (attempts.incrementAndGet() == 1) {
					if (failureKind.equals("NULL"))
						return null;
					throw failure;
				}
				return new LlmResponseDto("recovered");
			}
			@Override
			public String getModelName() {
				return "synthetic-background-test";
			}
		};
		var limited = new LlmService(client, 2);
		if (failureKind.equals("NULL"))
			assertThatThrownBy(() -> limited.tryGenerateBackgroundJudgment(request("review-1"))).isInstanceOfSatisfying(
					LlmException.class, e -> assertThat(e.getErrorCode()).isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
		else
			assertThatThrownBy(() -> limited.tryGenerateBackgroundJudgment(request("review-1"))).isSameAs(failure);
		assertThat(attempts.get()).isEqualTo(1);
		assertThat(limited.tryGenerateBackgroundJudgment(request("review-2")).orElseThrow().answer())
				.isEqualTo("recovered");
		assertThat(attempts.get()).isEqualTo(2);
	}

	@Test
	void interruptedBackgroundDoesNotCallModelOrLeakAdmission() throws Exception {
		try (var calls = new Calls()) {
			var limited = new LlmService(calls, 2);
			var failure = new AtomicReference<Throwable>();
			var interrupted = new AtomicBoolean();
			Thread thread = Thread.ofVirtual().start(() -> {
				Thread.currentThread().interrupt();
				try {
					limited.tryGenerateBackgroundJudgment(request("review-1"));
				} catch (Throwable e) {
					failure.set(e);
					interrupted.set(Thread.currentThread().isInterrupted());
				}
			});
			thread.join(5000);
			assertThat(thread.isAlive()).isFalse();
			assertThat(failure.get()).isInstanceOf(CancellationException.class);
			assertThat(interrupted.get()).isTrue();
			assertThat(calls.names).isEmpty();
			assertThat(limited.tryGenerateBackgroundJudgment(request("review-2"))).isPresent();
		}
	}

	@Test
	void backgroundDoesNotReportChatProgress() {
		try (var calls = new Calls()) {
			var limited = new LlmService(calls, 2);
			var phases = new ArrayList<AnswerProgress.Phase>();
			assertThat(AnswerProgress.observe(phases::add,
					() -> limited.tryGenerateBackgroundJudgment(request("review-1")))).isPresent();
			assertThat(phases).isEmpty();
		}
	}
}
