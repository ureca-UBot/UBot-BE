package com.ubot.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeExceptionsTest {

	@Test
	void typeNamesOuterAndInnermostCauseOnly() {
		var exception = new IllegalStateException("비공개 질문", new IllegalArgumentException("secret-token"));

		assertThat(SafeExceptions.type(exception)).isEqualTo("IllegalStateException/IllegalArgumentException");
	}

	@Test
	void maskedCopyKeepsStackTraceAndCausesButDropsMessages() {
		var cause = new IllegalArgumentException("secret-token");
		var exception = new IllegalStateException("비공개 질문", cause);

		Throwable masked = SafeExceptions.withoutMessages(exception);

		assertThat(masked.getMessage()).isEqualTo("java.lang.IllegalStateException (메시지 생략)");
		assertThat(masked.getStackTrace()).isEqualTo(exception.getStackTrace());
		assertThat(masked.getCause().getMessage()).isEqualTo("java.lang.IllegalArgumentException (메시지 생략)");
		assertThat(masked.getCause().getStackTrace()).isEqualTo(cause.getStackTrace());
		assertThat(masked.getCause().getCause()).isNull();
	}

	@Test
	void selfReferencingCauseDoesNotLoop() {
		var exception = new RuntimeException("비공개") {
			@Override
			public synchronized Throwable getCause() {
				return this;
			}
		};

		assertThat(SafeExceptions.withoutMessages(exception).getCause()).isNull();
	}
}
