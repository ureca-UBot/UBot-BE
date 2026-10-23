package com.ubot.chat.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

class ChatJobReadExecutorTest {

	private final ChatJobReadExecutor reads = new ChatJobReadExecutor(Duration.ofMillis(1500), 8);
	@AfterEach void close() { reads.close(); }

	@Test void interruptsTimedOutReadAndCanReadAgain() throws Exception {
		var interrupted = new CountDownLatch(1);
		long started = System.nanoTime();
		assertUnavailable(() -> reads.execute(request(), () -> {
			try { new CountDownLatch(1).await(); }
			catch (InterruptedException exception) { interrupted.countDown(); Thread.currentThread().interrupt(); }
			return "late";
		}));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
		assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
		assertThat(reads.execute(request(), () -> "recovered")).isEqualTo("recovered");
	}

	@Test void authenticationAndStatusShareOneDeadline() {
		var request = request();
		long started = System.nanoTime();
		reads.execute(request, () -> { pause(900); return "authenticated"; });
		assertUnavailable(() -> reads.execute(request, () -> { pause(900); return "status"; }));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
	}

	@Test void mapsDatabaseFailureButPreservesOwnershipFailure() {
		assertUnavailable(() -> reads.execute(request(), () -> {
			throw new DataAccessResourceFailureException("database unavailable");
		}));
		var forbidden = new ChatException(ChatErrorCode.JOB_NOT_FOUND);
		assertThatThrownBy(() -> reads.execute(request(), () -> { throw forbidden; })).isSameAs(forbidden);
	}

	@Test void rejectsExcessReadsWithoutQueuingWhileJdbcIgnoresCancellation() throws Exception {
		var started = new CountDownLatch(8);
		var release = new CountDownLatch(1);
		try (var callers = Executors.newFixedThreadPool(8)) {
			var pending = IntStream.range(0, 8).mapToObj(index -> callers.submit(() -> reads.execute(request(), () -> {
				started.countDown();
				boolean done = false;
				while (!done) {
					try { release.await(); done = true; }
					catch (InterruptedException ignored) {
						// 취소를 무시하는 JDBC 호출 재현
					}
				}
				return "released";
			}))).toList();
			try {
				assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
				long began = System.nanoTime();
				assertUnavailable(() -> reads.execute(request(), () -> "overflow"));
				assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofMillis(500));
			} finally { release.countDown(); }
			for (var task : pending) assertThat(task.get(1, TimeUnit.SECONDS)).isEqualTo("released");
		}
	}

	private static MockHttpServletRequest request() { return new MockHttpServletRequest("GET", "/chat/jobs/123"); }
	private static void pause(long milliseconds) {
		try { Thread.sleep(milliseconds); }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
	}
	private static void assertUnavailable(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ChatException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ChatErrorCode.JOB_QUERY_UNAVAILABLE));
	}
}
