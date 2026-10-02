package com.ubot.chat.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.chat.service.ChatAnswerTask;
import com.ubot.chat.service.ChatService;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.user.entity.User;
import jakarta.servlet.AsyncEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTest {
	ChatService service = mock(ChatService.class);
	CustomUserDetails principal = new CustomUserDetails(User.builder().id(1L).build());
	MockMvc mvc;

	@BeforeEach void setup() {
		var controller = new ChatController(service);
		ReflectionTestUtils.setField(controller, "responseTimeoutMillis", 180_000L);
		mvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
					public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == CustomUserDetails.class; }
					public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r,
							org.springframework.web.bind.support.WebDataBinderFactory f) { return principal; }
				}).build();
	}

	@Test void existingQuestionRouteUsesAuthenticatedUserAndSingleJsonResponse() throws Exception {
		var answer = new CompletableFuture<ChatResponseDto>();
		when(service.createChat(1L, "질문", null, null)).thenReturn(task(answer));
		var pending = mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\",\"userId\":999}")).andExpect(request().asyncStarted()).andReturn();
		verify(service).createChat(1L, "질문", null, null);
		org.assertj.core.api.Assertions.assertThat(pending.getResponse().getContentAsString()).isEmpty();
		answer.complete(ChatResponseDto.createSuccessAnswer("완성된 답변"));
		mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.data.answer").value("완성된 답변"))
				.andExpect(jsonPath("$.data.status").value("SUCCESS"))
				.andExpect(jsonPath("$.data.success").doesNotExist());
	}

	@Test void retryUsesKeyWithoutSessionOrQuestionIds() throws Exception {
		String key = "a".repeat(64);
		var answer = new CompletableFuture<ChatResponseDto>();
		when(service.retryChat(1L, key)).thenReturn(task(answer));
		var pending = mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", key))
				.andExpect(request().asyncStarted()).andReturn();
		verify(service).retryChat(1L, key);
		org.assertj.core.api.Assertions.assertThat(pending.getResponse().getContentAsString()).isEmpty();
		answer.completeExceptionally(new ChatException(LlmErrorCode.LLM_TIMEOUT,
				new ChatResponseDto(LlmErrorCode.LLM_TIMEOUT.getMessage(), "FAIL", key, 2, true)));
		mvc.perform(asyncDispatch(pending)).andExpect(status().isGatewayTimeout())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("LLM-004"))
				.andExpect(jsonPath("$.message").value(LlmErrorCode.LLM_TIMEOUT.getMessage()))
				.andExpect(jsonPath("$.data.answer").value(LlmErrorCode.LLM_TIMEOUT.getMessage()))
				.andExpect(jsonPath("$.data.status").value("FAIL"))
				.andExpect(jsonPath("$.data.success").doesNotExist())
				.andExpect(jsonPath("$.data.idempotencyKey").value(key))
				.andExpect(jsonPath("$.data.attemptCount").value(2))
				.andExpect(jsonPath("$.data.retryable").value(true));
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3})
	void timeoutUsesChatSpecificErrorResponse(int attemptCount) throws Exception {
		boolean retry = attemptCount > 1;
		String key = "a".repeat(64);
		var answer = new CompletableFuture<ChatResponseDto>();
		var request = retry
				? post("/chat/questions/retries").header("Idempotency-Key", key)
				: post("/chat/questions").contentType("application/json").content("{\"question\":\"질문\"}");
		var generation = new ChatAnswerTask(answer, () -> timeoutResponse(attemptCount, attemptCount < 3));
		if (retry) {
			when(service.retryChat(1L, key)).thenReturn(generation);
		} else {
			when(service.createChat(1L, "질문", null, null)).thenReturn(generation);
		}
		var pending = mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
		var asyncContext = (MockAsyncContext) pending.getRequest().getAsyncContext();
		// 실제 제한 시간을 기다리지 않고 서블릿의 타임아웃 이벤트를 전달합니다.
		for (var listener : asyncContext.getListeners()) {
			listener.onTimeout(new AsyncEvent(asyncContext));
		}

		org.assertj.core.api.Assertions.assertThat(answer.isCompletedExceptionally()).isTrue();
		org.assertj.core.api.Assertions.assertThat(
				answer.complete(ChatResponseDto.createSuccessAnswer("늦게 생성된 답변"))).isFalse();
		mvc.perform(asyncDispatch(pending)).andExpect(status().isGatewayTimeout())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("CHAT-016"))
				.andExpect(jsonPath("$.message").value("답변 생성 시간이 초과되었습니다."))
				.andExpect(jsonPath("$.data.status").value("FAIL"))
				.andExpect(jsonPath("$.data.idempotencyKey").value(key))
				.andExpect(jsonPath("$.data.attemptCount").value(attemptCount))
				.andExpect(jsonPath("$.data.retryable").value(attemptCount < 3));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void timeoutWaitsForFailureStorageAndReportsStorageErrors(boolean storageFails) throws Exception {
		var answer = new CompletableFuture<ChatResponseDto>();
		var saveStarted = new CountDownLatch(1);
		var allowSaveToFinish = new CountDownLatch(1);
		var storageFailure = new DataAccessResourceFailureException("database secret");
		when(service.createChat(1L, "질문", null, null)).thenReturn(new ChatAnswerTask(answer, () -> {
			saveStarted.countDown();
			try {
				if (!allowSaveToFinish.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test save timeout");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
			if (storageFails) {
				throw storageFailure;
			}
			return timeoutResponse(1, true);
		}));
		var pending = mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}")).andExpect(request().asyncStarted()).andReturn();
		var context = (MockAsyncContext) pending.getRequest().getAsyncContext();
		var callbackFailure = new AtomicReference<Throwable>();
		Thread callback = Thread.ofVirtual().start(() -> {
			try {
				for (var listener : context.getListeners()) {
					listener.onTimeout(new AsyncEvent(context));
				}
			} catch (Throwable exception) {
				callbackFailure.set(exception);
			}
		});
		try {
			assertThat(saveStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(answer.isDone()).isFalse();
			assertThat(pending.getRequest().isAsyncStarted()).isTrue();
			assertThat(pending.getResponse().getContentAsString()).isEmpty();
			assertThat(org.springframework.web.context.request.async.WebAsyncUtils.getAsyncManager(pending.getRequest()).hasConcurrentResult()).as("실패 저장이 끝나기 전에는 응답을 확정하지 않음").isFalse();
		} finally {
			allowSaveToFinish.countDown();
			callback.join(5_000L);
			if (callback.isAlive()) {
				callback.interrupt();
			}
		}
		assertThat(callback.isAlive()).isFalse();
		assertThat(callbackFailure.get()).isNull();
		var result = mvc.perform(asyncDispatch(pending))
				.andExpect(status().is(storageFails ? 500 : 504))
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value(storageFails ? "G-005" : "CHAT-016"))
				.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("database secret");
		if (storageFails) {
			assertThat(result.getResolvedException()).isSameAs(storageFailure);
		}
	}

	@Test
	void completedResponseDoesNotRunTimeoutStorage() throws Exception {
		var answer = new CompletableFuture<ChatResponseDto>();
		Runnable timeoutAction = mock(Runnable.class);
		when(service.createChat(1L, "질문", null, null)).thenReturn(new ChatAnswerTask(answer, () -> {
			timeoutAction.run();
			return timeoutResponse(1, true);
		}));
		var pending = mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}")).andExpect(request().asyncStarted()).andReturn();
		answer.complete(ChatResponseDto.createSuccessAnswer("완성된 답변"));

		var context = (MockAsyncContext) pending.getRequest().getAsyncContext();
		for (var listener : context.getListeners()) {
			listener.onTimeout(new AsyncEvent(context));
		}

		verifyNoInteractions(timeoutAction);
		mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
				.andExpect(jsonPath("$.data.answer").value("완성된 답변"));
	}

	private ChatAnswerTask task(CompletableFuture<ChatResponseDto> answer) {
		return new ChatAnswerTask(answer, () -> timeoutResponse(1, true));
	}

	private ChatResponseDto timeoutResponse(int attemptCount, boolean retryable) {
		return new ChatResponseDto(ChatErrorCode.RESPONSE_TIMEOUT.getMessage(), "FAIL",
				"a".repeat(64), attemptCount, retryable);
	}

	@Test void invalidRetryRequestUsesCommonErrorResponse() throws Exception {
		when(service.retryChat(1L, "invalid-key"))
				.thenThrow(new ChatException(ChatErrorCode.INVALID_CHAT_RETRY_REQUEST));

		mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", "invalid-key"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("CHAT-011"))
				.andExpect(jsonPath("$.data").doesNotExist());
		verify(service).retryChat(1L, "invalid-key");
	}

	@Test void blankQuestionIsRejectedBeforeService() throws Exception {
		mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\" \"}")).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test void locationIsPassedToService() throws Exception {
		when(service.createChat(1L, "근처 매장", 37.498, 127.028)).thenReturn(task(new CompletableFuture<>()));

		mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"근처 매장\",\"latitude\":37.498,\"longitude\":127.028}"))
				.andExpect(request().asyncStarted());
		verify(service).createChat(1L, "근처 매장", 37.498, 127.028);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"question\":\"질문\",\"latitude\":37.498}",
			"{\"question\":\"질문\",\"longitude\":127.028}",
			"{\"question\":\"질문\",\"latitude\":91,\"longitude\":127.028}",
			"{\"question\":\"질문\",\"latitude\":37.498,\"longitude\":181}"
	})
	void invalidLocationIsRejectedBeforeService(String body) throws Exception {
		mvc.perform(post("/chat/questions").contentType("application/json").content(body))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test void sessionApiNoLongerExists() throws Exception {
		// 팀의 공통 예외 처리기는 없는 경로도 공통 오류로 변환하므로 핸들러 부재를 확인합니다.
		org.assertj.core.api.Assertions.assertThat(mvc.perform(post("/chat/sessions")).andReturn().getHandler()).isNull();
		org.assertj.core.api.Assertions.assertThat(mvc.perform(post("/chat/sessions/1/questions")).andReturn().getHandler()).isNull();
		verifyNoInteractions(service);
	}
}
