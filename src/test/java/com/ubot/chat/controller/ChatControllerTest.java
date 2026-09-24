package com.ubot.chat.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.service.ChatService;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
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

	@Test void existingQuestionRouteUsesAuthenticatedUserAndSse() throws Exception {
		var emitter = new SseEmitter();
		when(service.createChat(1L, "질문")).thenReturn(emitter);
		mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\",\"userId\":999}")).andExpect(request().asyncStarted());
		verify(service).createChat(1L, "질문");
		emitter.complete();
	}

	@Test void retryUsesKeyWithoutSessionOrQuestionIds() throws Exception {
		String key = "a".repeat(64);
		var emitter = new SseEmitter();
		when(service.retryChat(1L, key)).thenReturn(emitter);
		mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", key))
				.andExpect(request().asyncStarted());
		verify(service).retryChat(1L, key);
		emitter.complete();
	}

	@Test void unauthenticatedRequestIsRejected() throws Exception {
		principal = null;
		mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"질문\"}")).andExpect(status().isUnauthorized());
		verifyNoInteractions(service);
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
