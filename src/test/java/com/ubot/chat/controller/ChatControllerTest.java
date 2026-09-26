package com.ubot.chat.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.service.ChatService;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.user.entity.User;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTest {
	ChatService service = mock(ChatService.class);
	CustomUserDetails principal = new CustomUserDetails(User.builder().id(1L).build());
	MockMvc mvc;

	@BeforeEach void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new ChatController(service))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
					public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == CustomUserDetails.class; }
					public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r,
							org.springframework.web.bind.support.WebDataBinderFactory f) { return principal; }
				}).build();
	}

	@Test void existingQuestionRouteUsesAuthenticatedUserAndSingleJsonResponse() throws Exception {
		var answer = new CompletableFuture<ChatResponseDto>();
		when(service.createChat(1L, "질문")).thenReturn(answer);
		var pending = mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\",\"userId\":999}")).andExpect(request().asyncStarted()).andReturn();
		verify(service).createChat(1L, "질문");
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
		when(service.retryChat(1L, key)).thenReturn(answer);
		var pending = mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", key))
				.andExpect(request().asyncStarted()).andReturn();
		verify(service).retryChat(1L, key);
		org.assertj.core.api.Assertions.assertThat(pending.getResponse().getContentAsString()).isEmpty();
		answer.complete(new ChatResponseDto("생성 실패", "FAIL", key, 2, true));
		mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.message").isString())
				.andExpect(jsonPath("$.data.answer").value("생성 실패"))
				.andExpect(jsonPath("$.data.status").value("FAIL"))
				.andExpect(jsonPath("$.data.success").doesNotExist())
				.andExpect(jsonPath("$.data.idempotencyKey").value(key))
				.andExpect(jsonPath("$.data.attemptCount").value(2))
				.andExpect(jsonPath("$.data.retryable").value(true));
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

	@Test void sessionApiNoLongerExists() throws Exception {
		// 팀의 공통 예외 처리기는 없는 경로도 공통 오류로 변환하므로 핸들러 부재를 확인합니다.
		org.assertj.core.api.Assertions.assertThat(mvc.perform(post("/chat/sessions")).andReturn().getHandler()).isNull();
		org.assertj.core.api.Assertions.assertThat(mvc.perform(post("/chat/sessions/1/questions")).andReturn().getHandler()).isNull();
		verifyNoInteractions(service);
	}
}
