package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

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
}
