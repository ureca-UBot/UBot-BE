package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.llm.config.LlmClientFactory;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class LlmRecoveryLoggingTest {

	@Test
	void upstream5xxLogsStatusAndRecoveryWithoutBodyOrExceptionText(CapturedOutput output) throws IOException {
		try (var server = new StubLlmServer("/v1/chat/completions")) {
			server.respond(503, "{\"error\":\"비공개 질문 Bearer secret-token\"}");
			server.respond("""
					{"choices":[{"message":{"role":"assistant","content":"비공개 답변"},"finish_reason":"stop"}]}
					""");
			var waits = new AtomicInteger();
			String answer = LlmRecovery.observe(Duration.ofMillis(1),
					() -> {}, waits::incrementAndGet, () -> client(server).generateAnswer(question()).answer());

			assertThat(answer).isEqualTo("비공개 답변");
			assertThat(waits.get()).isEqualTo(1);
			assertThat(server.requests()).hasSize(2);
			assertThat(output.getAll()).contains("HTTP상태=503", "오류코드=LLM-003", "재시도횟수=1", "대기시간=",
					"모델 연결 복구 후 호출이 성공했습니다")
					.doesNotContain("비공개 질문", "비공개 답변", "secret-token", "LLM 서버가 오류 응답을 반환했습니다");
		}
	}

	@Test
	void nonRetryable4xxLogsServerConfigProblemOnceWithoutWaiting(CapturedOutput output) throws IOException {
		try (var server = new StubLlmServer("/v1/chat/completions")) {
			server.respond(400, "{\"error\":\"비공개 질문 secret-token\"}");
			var waits = new AtomicInteger();

			assertThatThrownBy(() -> LlmRecovery.observe(Duration.ofMillis(1),
					() -> {}, waits::incrementAndGet, () -> client(server).generateAnswer(question())))
					.isInstanceOfSatisfying(LlmException.class,
							exception -> assertThat(exception.getErrorCode()).isEqualTo(LlmErrorCode.LLM_SERVICE_UNAVAILABLE));

			assertThat(waits.get()).isZero();
			assertThat(server.requests()).hasSize(1);
			assertThat(output.getAll()).containsOnlyOnce("LLM 서버가 오류 응답을 반환했습니다")
					.contains("모델=ubot-chat", "상태코드=400")
					.doesNotContain("복구를 기다립니다", "비공개 질문", "secret-token");
		}
	}

	@Test
	void callsOutsideWorkerDoNotRetryAndLeave5xxLoggingToCaller(CapturedOutput output) throws IOException {
		try (var server = new StubLlmServer("/v1/chat/completions")) {
			server.respond(503, "{\"error\":\"비공개 질문 secret-token\"}");

			assertThatThrownBy(() -> client(server).generateAnswer(question())).isInstanceOf(LlmException.class);

			assertThat(server.requests()).hasSize(1);
			assertThat(output.getAll())
					.doesNotContain("LLM 서버가 오류 응답을 반환했습니다", "복구를 기다립니다", "비공개 질문", "secret-token");
		}
	}

	private LlmClient client(StubLlmServer server) {
		return new LlmClientFactory().createOpenAiCompatible(RestClient.builder(), server.url() + "/v1",
				"ubot-chat", Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	private LlmRequestDto question() {
		return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "비공개 질문")));
	}
}
