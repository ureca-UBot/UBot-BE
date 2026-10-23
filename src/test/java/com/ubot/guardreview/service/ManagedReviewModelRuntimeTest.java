package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.guardreview.exception.ReviewLeaseLostException;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

class ManagedReviewModelRuntimeTest {
	@Test
	void 작업_시작에_준비를_요청하고_종료할때_그_사용권만_해제한다() {
		List<String> operations = new ArrayList<>();
		var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
			operations.add(request.getMethod().name() + " " + request.getURI().getPath());
			assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer test-token");
			if (request.getMethod().name().equals("PUT")) {
				assertThat(new String(body, StandardCharsets.UTF_8)).contains(ReviewModelClient.MODEL);
			}
			var reply = new MockClientHttpResponse(
					("{\"ready\":true,\"model\":\"" + ReviewModelClient.MODEL + "\"}").getBytes(StandardCharsets.UTF_8),
					HttpStatus.OK);
			reply.getHeaders().setContentType(MediaType.APPLICATION_JSON);
			return reply;
		});
		var runtime = new ManagedReviewModelRuntime(builder, "http://control.invalid", "test-token");
		Runnable renew = mock(Runnable.class);
		try {
			assertThat(operations).isEmpty();
			try (var session = runtime.acquire(9, renew)) {
				session.ensureValid();
				assertThat(operations).hasSize(1);
			}
			verify(renew).run();
			assertThat(operations).hasSize(2);
			assertThat(operations.getFirst()).startsWith("PUT /leases/");
			assertThat(operations.getLast()).isEqualTo(operations.getFirst().replace("PUT ", "DELETE "));
		} finally {
			runtime.close();
		}
	}

	@Test
	void 제어_연결이_없으면_임의로_모델을_실행하거나_기존모델로_대체하지_않는다() {
		var runtime = new ManagedReviewModelRuntime(RestClient.builder(), "", "");
		Runnable renew = mock(Runnable.class);
		try {
			assertThatThrownBy(() -> runtime.acquire(9, renew)).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("실행 제어 주소");
			verifyNoInteractions(renew);
		} finally {
			runtime.close();
		}
	}

	@Test
	void GPU_대기_응답은_분석실패와_구분하고_시작_실패한_사용권도_회수한다() {
		List<String> operations = new ArrayList<>();
		var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
			operations.add(request.getMethod().name());
			var reply = new MockClientHttpResponse("{\"error\":\"GPU_BUSY\"}".getBytes(StandardCharsets.UTF_8),
					request.getMethod().name().equals("PUT") ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK);
			reply.getHeaders().setContentType(MediaType.APPLICATION_JSON);
			return reply;
		});
		var runtime = new ManagedReviewModelRuntime(builder, "http://control.invalid", "test-token");
		try {
			assertThatThrownBy(() -> runtime.acquire(9, () -> {
			})).isInstanceOf(ReviewModelRuntime.Busy.class);
			assertThat(operations).containsExactly("PUT", "DELETE");
		} finally {
			runtime.close();
		}
	}

	@Test
	void heartbeat_통신이_한번_실패해도_사용권을_유지하고_다음_성공으로_복구한다() {
		try (var fixture = new HeartbeatFixture()) {
			var session = fixture.runtime.acquire(9, () -> {
			});
			fixture.replies.add(new ResourceAccessException("temporary timeout"));
			fixture.ticks.getFirst().run();
			session.ensureValid();
			assertThat(fixture.deletes()).isZero();
			fixture.ticks.getFirst().run();
			session.ensureValid();
			assertThat(fixture.deletes()).isZero();
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {408, 429, 500, 503})
	void 일시적인_HTTP_오류는_즉시_사용권을_반납하지_않는다(int status) {
		try (var fixture = new HeartbeatFixture()) {
			var session = fixture.runtime.acquire(9, () -> {
			});
			fixture.replies.add(HttpStatus.valueOf(status));
			fixture.ticks.getFirst().run();
			session.ensureValid();
			assertThat(fixture.deletes()).isZero();
		}
	}

	@Test
	void heartbeat_성공은_연속_실패_횟수를_초기화한다() {
		try (var fixture = new HeartbeatFixture()) {
			var session = fixture.runtime.acquire(9, () -> {
			});
			fixture.replies.addAll(List.of(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
					HttpStatus.OK, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE));
			for (int i = 0; i < 5; i++)
				fixture.ticks.getFirst().run();
			session.ensureValid();
			assertThat(fixture.deletes()).isZero();
		}
	}

	@Test
	void heartbeat가_연속_세번_실패하면_해당_사용권을_해제한다() {
		try (var fixture = new HeartbeatFixture()) {
			var session = fixture.runtime.acquire(9, () -> {
			});
			fixture.replies.addAll(List.of(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
					HttpStatus.SERVICE_UNAVAILABLE));
			fixture.ticks.getFirst().run();
			fixture.ticks.getFirst().run();
			session.ensureValid();
			fixture.ticks.getFirst().run();
			assertThatThrownBy(session::ensureValid).isInstanceOf(IllegalStateException.class);
			assertThat(fixture.deletes()).isEqualTo(1);
			verify(fixture.futures.getFirst()).cancel(false);
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403, 404, 409})
	void 인증_실패나_없는_사용권은_재시도하지_않는다(int status) {
		try (var fixture = new HeartbeatFixture()) {
			var session = fixture.runtime.acquire(9, () -> {
			});
			fixture.replies.add(HttpStatus.valueOf(status));
			fixture.ticks.getFirst().run();
			assertThatThrownBy(session::ensureValid).isInstanceOf(IllegalStateException.class);
			assertThat(fixture.deletes()).isEqualTo(1);
		}
	}

	@Test
	void 작업_소유권을_잃으면_모델_heartbeat를_재시도하지_않는다() {
		try (var fixture = new HeartbeatFixture()) {
			Runnable check = mock(Runnable.class);
			var session = fixture.runtime.acquire(9, check);
			doThrow(new ReviewLeaseLostException()).when(check).run();
			fixture.ticks.getFirst().run();
			assertThatThrownBy(session::ensureValid).isInstanceOf(IllegalStateException.class)
					.hasCauseInstanceOf(ReviewLeaseLostException.class);
			assertThat(fixture.operations).noneMatch(op -> op.startsWith("POST "));
			assertThat(fixture.deletes()).isEqualTo(1);
		}
	}

	@Test
	void 연속_실패_한도_전이어도_확인된_사용권_유효기간을_넘기지_않는다() {
		try (var fixture = new HeartbeatFixture()) {
			var session = fixture.runtime.acquire(9, () -> {
			});
			fixture.replies.add(new ResourceAccessException("temporary timeout"));
			fixture.ticks.getFirst().run();
			fixture.now.set(Duration.ofSeconds(899).toNanos());
			session.ensureValid();
			fixture.now.set(Duration.ofSeconds(900).toNanos());
			assertThatThrownBy(session::ensureValid).isInstanceOf(IllegalStateException.class);
			assertThat(fixture.deletes()).isEqualTo(1);
		}
	}

	@Test
	void 준비가_완료된_사용권에만_heartbeat를_예약한다() {
		try (var fixture = new HeartbeatFixture()) {
			assertThat(fixture.ticks).isEmpty();
			var session = fixture.runtime.acquire(9, () -> {
			});
			assertThat(fixture.scheduledDuringPreparation).isFalse();
			assertThat(fixture.ticks).hasSize(1);
			session.ensureValid();
		}
	}

	@Test
	void 한_사용권의_연속_실패는_다른_작업의_사용권을_종료하지_않는다() {
		try (var fixture = new HeartbeatFixture()) {
			var first = fixture.runtime.acquire(9, () -> {
			});
			var second = fixture.runtime.acquire(10, () -> {
			});
			String firstPath = fixture.operations.getFirst().substring("PUT ".length());
			fixture.replies.addAll(List.of(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
					HttpStatus.SERVICE_UNAVAILABLE));
			for (int i = 0; i < 3; i++)
				fixture.ticks.getFirst().run();
			assertThatThrownBy(first::ensureValid).isInstanceOf(IllegalStateException.class);
			fixture.ticks.get(1).run();
			second.ensureValid();
			assertThat(fixture.operations.stream().filter(op -> op.startsWith("DELETE ")))
					.containsExactly("DELETE " + firstPath);
			verify(fixture.futures.get(1), never()).cancel(false);
		}
	}

	@Test
	void 일시_실패_후_작업이_끝나면_사용권을_반납하고_추가_heartbeat를_보내지_않는다() {
		try (var fixture = new HeartbeatFixture()) {
			var session = fixture.runtime.acquire(9, () -> {
			});
			fixture.replies.add(HttpStatus.SERVICE_UNAVAILABLE);
			fixture.ticks.getFirst().run();
			session.close();
			fixture.ticks.getFirst().run();
			assertThat(fixture.operations.stream().filter(op -> op.startsWith("POST ")).count()).isEqualTo(1);
			assertThat(fixture.deletes()).isEqualTo(1);
		}
	}

	@Test
	void 갱신_응답이_늦어져도_네트워크_지연만큼_사용권_기간을_늘리지_않는다() {
		try (var fixture = new HeartbeatFixture()) {
			var session = fixture.runtime.acquire(9, () -> {
			});
			fixture.postDelayNanos = Duration.ofSeconds(900).toNanos();
			fixture.ticks.getFirst().run();
			assertThatThrownBy(session::ensureValid).isInstanceOf(IllegalStateException.class);
			assertThat(fixture.deletes()).isEqualTo(1);
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {408, 429, 500, 503, 504})
	void 일시적인_준비_HTTP_실패는_사용권을_회수하고_기존_재시도_오류로_전달한다(int status) {
		try (var fixture = new HeartbeatFixture()) {
			fixture.preparationReplies.add(HttpStatus.valueOf(status));
			LlmErrorCode expected = status == 408 || status == 504
					? LlmErrorCode.LLM_TIMEOUT
					: LlmErrorCode.LLM_SERVICE_UNAVAILABLE;
			assertThatThrownBy(() -> fixture.runtime.acquire(9, () -> {
			})).isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
			assertThat(fixture.operations.stream().map(op -> op.split(" ")[0])).containsExactly("PUT", "DELETE");
			assertThat(fixture.ticks).isEmpty();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void 준비_연결_실패와_시간초과는_구분하고_다음_준비_시도는_정상적으로_진행한다(boolean timeout) {
		try (var fixture = new HeartbeatFixture()) {
			fixture.preparationReplies.add(new ResourceAccessException("temporary preparation failure",
					timeout ? new SocketTimeoutException("timeout") : new ConnectException("offline")));
			LlmErrorCode expected = timeout ? LlmErrorCode.LLM_TIMEOUT : LlmErrorCode.LLM_SERVICE_UNAVAILABLE;
			assertThatThrownBy(() -> fixture.runtime.acquire(9, () -> {
			})).isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
			assertThat(fixture.deletes()).isEqualTo(1);
			assertThat(fixture.ticks).isEmpty();
			var session = fixture.runtime.acquire(9, () -> {
			});
			session.ensureValid();
			assertThat(fixture.ticks).hasSize(1);
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 409})
	void 준비_설정이나_인증_오류는_일시적_LLM_장애로_바꾸지_않는다(int status) {
		try (var fixture = new HeartbeatFixture()) {
			fixture.preparationReplies.add(HttpStatus.valueOf(status));
			assertThatThrownBy(() -> fixture.runtime.acquire(9, () -> {
			})).isNotInstanceOf(LlmException.class);
			assertThat(fixture.deletes()).isEqualTo(1);
			assertThat(fixture.ticks).isEmpty();
		}
	}

	@Test
	void 다른_모델의_준비_응답은_재시도_대상으로_바꾸지_않는다() {
		try (var fixture = new HeartbeatFixture()) {
			fixture.preparationReplies.add("{\"ready\":true,\"model\":\"wrong-model\"}");
			assertThatThrownBy(() -> fixture.runtime.acquire(9, () -> {
			})).isInstanceOf(IllegalStateException.class).hasMessageContaining("일치하지");
			assertThat(fixture.deletes()).isEqualTo(1);
			assertThat(fixture.ticks).isEmpty();
		}
	}

	private static class HeartbeatFixture implements AutoCloseable {
		final List<String> operations = new ArrayList<>();
		final List<Runnable> ticks = new ArrayList<>();
		final List<ScheduledFuture<?>> futures = new ArrayList<>();
		final Deque<Object> replies = new ArrayDeque<>();
		final Deque<Object> preparationReplies = new ArrayDeque<>();
		final AtomicLong now = new AtomicLong();
		final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		final ManagedReviewModelRuntime runtime;
		boolean scheduledDuringPreparation;
		long postDelayNanos;

		HeartbeatFixture() {
			when(scheduler.scheduleWithFixedDelay(any(Runnable.class), eq(30L), eq(30L), eq(TimeUnit.SECONDS)))
					.thenAnswer(call -> {
						ticks.add(call.getArgument(0));
						ScheduledFuture<?> future = mock(ScheduledFuture.class);
						futures.add(future);
						return future;
					});
			var builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
				String method = request.getMethod().name();
				operations.add(method + " " + request.getURI().getPath());
				HttpStatus status = HttpStatus.OK;
				String responseBody = "{\"ready\":true,\"model\":\"" + ReviewModelClient.MODEL + "\"}";
				if (method.equals("PUT")) {
					scheduledDuringPreparation = !ticks.isEmpty();
					Object next = preparationReplies.poll();
					if (next instanceof RuntimeException failure)
						throw failure;
					if (next instanceof HttpStatus responseStatus)
						status = responseStatus;
					if (next instanceof String text)
						responseBody = text;
				}
				if (method.equals("POST")) {
					now.addAndGet(postDelayNanos);
					Object next = replies.poll();
					if (next instanceof RuntimeException failure)
						throw failure;
					if (next instanceof HttpStatus responseStatus)
						status = responseStatus;
				}
				var reply = new MockClientHttpResponse(responseBody.getBytes(StandardCharsets.UTF_8), status);
				reply.getHeaders().setContentType(MediaType.APPLICATION_JSON);
				return reply;
			});
			runtime = new ManagedReviewModelRuntime(builder, "http://control.invalid", "test-token",
					Duration.ofSeconds(3), Duration.ofSeconds(300), scheduler, now::get);
		}

		long deletes() {
			return operations.stream().filter(op -> op.startsWith("DELETE ")).count();
		}

		@Override
		public void close() {
			runtime.close();
		}
	}
}
