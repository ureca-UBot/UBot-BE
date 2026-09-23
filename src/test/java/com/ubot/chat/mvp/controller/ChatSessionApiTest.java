package com.ubot.chat.mvp.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.mvp.domain.ChatAttempt;
import com.ubot.chat.mvp.dto.ChatAttemptResponse;
import com.ubot.chat.mvp.dto.ChatSessionResponse;
import com.ubot.chat.mvp.service.ChatStreamService;
import com.ubot.common.GlobalExceptionHandler;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP route/validation tests. Authentication filters and the real database are not started. */
class ChatSessionApiTest {
    private final ChatStreamService service = mock(ChatStreamService.class);
    private CustomUserDetails principal;
    private MockMvc mvc;
    private static final String KEY = "a".repeat(64);

    @BeforeEach void setup() {
        principal = mock(CustomUserDetails.class);
        when(principal.getUserId()).thenReturn(1L);
        mvc = MockMvcBuilders.standaloneSetup(new ChatStreamController(service))
                .setControllerAdvice(new ChatStreamExceptionHandler(), new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }
                    @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory factory) { return principal; }
                }).build();
    }

    @Test void createsSessionAtSpecifiedRouteForAuthenticatedUser() throws Exception {
        when(service.createSession(1)).thenReturn(new ChatSessionResponse(20, LocalDateTime.of(2026, 9, 23, 10, 0)));
        mvc.perform(post("/chat/sessions")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").value(20));
        verify(service).createSession(1);
    }

    @Test void initialQuestionNeedsNoClientKeyAndUsesAuthenticatedIdentity() throws Exception {
        SseEmitter emitter = new SseEmitter();
        when(service.start(1, 20, "질문")).thenReturn(emitter);
        mvc.perform(post("/chat/sessions/20/questions")
                .contentType("application/json").content("{\"question\":\"질문\",\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        verify(service).start(1, 20, "질문");
        emitter.complete();
    }

    @Test void retryRouteForwardsTheOriginalServerKeyUnchanged() throws Exception {
        SseEmitter emitter = new SseEmitter();
        when(service.retry(1, 20, 10, KEY)).thenReturn(emitter);
        mvc.perform(post("/chat/sessions/20/questions/10/retries").header("Idempotency-Key", KEY))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        verify(service).retry(1, 20, 10, KEY);
        emitter.complete();
    }

    @Test void statusRouteReturnsResultInCommonEnvelope() throws Exception {
        var response = ChatAttemptResponse.from(new ChatAttempt(101, 10, 1, 20,
                "질문", 1, "SUCCESS", "key", "답변", null, null));
        when(service.findQuestion(1, 20, 10)).thenReturn(response);
        mvc.perform(get("/chat/sessions/20/questions/10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(20))
                .andExpect(jsonPath("$.data.questionId").value(10))
                .andExpect(jsonPath("$.data.answer").value("답변"));
    }

    @Test void retryWithoutTheServerKeyIsRejectedBeforeGeneration() throws Exception {
        mvc.perform(post("/chat/sessions/20/questions/10/retries").contentType("application/json")
                .content("{\"question\":\"질문\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHAT-110"));
        verifyNoInteractions(service);
    }

    @Test void blankQuestionIsRejectedBeforeStartingGeneration() throws Exception {
        mvc.perform(post("/chat/sessions/20/questions")
                .contentType("application/json").content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void missingPrincipalCannotCreateASession() throws Exception {
        principal = null;
        mvc.perform(post("/chat/sessions")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/chat/questions/stream", "/chat/questions/10/retry"})
    void removedCustomPostRoutesHaveNoHandler(String path) throws Exception {
        assertThat(mvc.perform(post(path)).andReturn().getHandler()).isNull();
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/chat/questions/10/result", "/chat/requests/key/result"})
    void removedCustomGetRoutesHaveNoHandler(String path) throws Exception {
        assertThat(mvc.perform(get(path)).andReturn().getHandler()).isNull();
        verifyNoInteractions(service);
    }
}
