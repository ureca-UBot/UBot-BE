package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;

class LlmRecoveryTest {

	@Test
	void doesNotRetryUnexpectedErrorTranslatedAsUnavailable() {
		// translateException은 원인을 모르는 예외도 LLM_SERVICE_UNAVAILABLE로 바꿈. 코드 오류는 기다려도 낫지 않으므로 한 번만 호출
		var calls = new AtomicInteger();
		assertThatThrownBy(() -> LlmRecovery.observe(Duration.ofMillis(5), () -> {},
				() -> { throw new AssertionError("코드 오류를 재시도하면 안 됩니다."); },
				() -> LlmRecovery.call(() -> {
					calls.incrementAndGet();
					throw new LlmException(LlmErrorCode.LLM_SERVICE_UNAVAILABLE, new IllegalStateException("요청 본문 생성 실패"));
				})))
				.isInstanceOfSatisfying(LlmException.class,
						error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_SERVICE_UNAVAILABLE));
		assertThat(calls.get()).isEqualTo(1);
	}
}
