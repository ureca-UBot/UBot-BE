package com.ubot.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.ai.dto.Location;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.service.ChatAnswerTask;
import com.ubot.chat.service.ChatService;
import com.ubot.chat.util.ClientIpResolver;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.faq.enums.Intent;
import com.ubot.guest.session.GuestConversationService;
import com.ubot.user.entity.User;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@DisplayName("의도 재검색 컨트롤러 테스트")
class ChatControllerResearchTest {
    private static final String KEY = "a".repeat(64);

    ChatService service = mock(ChatService.class);
    ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    GuestConversationService guestConversationService = mock(GuestConversationService.class);
    CustomUserDetails principal = new CustomUserDetails(User.builder().id(1L).build());
    MockMvc mvc;

    @BeforeEach
    void setup() {
        var controller = new ChatController(service, clientIpResolver, guestConversationService);
        ReflectionTestUtils.setField(controller, "responseTimeoutMillis", 180_000L);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) {
                        return p.getParameterType() == CustomUserDetails.class;
                    }

                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r,
                            org.springframework.web.bind.support.WebDataBinderFactory f) {
                        return principal;
                    }
                }).build();
    }

    @Test
    @DisplayName("회원은 헤더의 키와 본문의 의도로 재검색하고, 완료되면 답변을 그대로 돌려준다")
    void memberResearchPassesKeyAndIntent() throws Exception {
        var answer = new CompletableFuture<ChatResponseDto>();
        when(service.researchChat(1L, KEY, Intent.STORE_DATA, null, null)).thenReturn(task(answer));

        var pending = mvc.perform(post("/chat/questions/research").header("Idempotency-Key", KEY)
                .contentType("application/json").content("{\"intent\":\"STORE_DATA\"}"))
                .andExpect(request().asyncStarted()).andReturn();

        verify(service).researchChat(1L, KEY, Intent.STORE_DATA, null, null);
        verify(guestConversationService).claimConversation(any(), eq(1L));
        answer.complete(ChatResponseDto.createSuccessAnswer("재검색 답변"));
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.answer").value("재검색 답변"));
    }

    @Test
    @DisplayName("좌표를 함께 보내면 Location으로 서비스에 전달한다")
    void coordinatesAreForwardedAsLocation() throws Exception {
        when(service.researchChat(1L, KEY, Intent.STORE_DATA, new Location(37.5, 127.0), null))
                .thenReturn(task(new CompletableFuture<>()));

        mvc.perform(post("/chat/questions/research").header("Idempotency-Key", KEY)
                .contentType("application/json")
                .content("{\"intent\":\"STORE_DATA\",\"latitude\":37.5,\"longitude\":127.0}"))
                .andExpect(request().asyncStarted());

        verify(service).researchChat(1L, KEY, Intent.STORE_DATA, new Location(37.5, 127.0), null);
    }

    @Test
    @DisplayName("로그인하지 않은 요청은 CHAT-002로 거절하고 서비스를 호출하지 않는다")
    void guestIsRejected() throws Exception {
        principal = null;

        mvc.perform(post("/chat/questions/research").header("Idempotency-Key", KEY)
                .contentType("application/json").content("{\"intent\":\"GENERAL\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CHAT-002"));

        verifyNoInteractions(service);
        verify(guestConversationService, never()).claimConversation(any(), any());
    }

    @Test
    @DisplayName("의도가 없거나 알 수 없는 값이면 400으로 거절한다")
    void invalidIntentIsRejected() throws Exception {
        mvc.perform(post("/chat/questions/research").header("Idempotency-Key", KEY)
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/chat/questions/research").header("Idempotency-Key", KEY)
                .contentType("application/json").content("{\"intent\":\"FOO\"}")).andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("위도·경도 쌍이 맞지 않거나 범위를 벗어나면 400으로 거절한다")
    void invalidCoordinatesAreRejected() throws Exception {
        mvc.perform(post("/chat/questions/research").header("Idempotency-Key", KEY)
                .contentType("application/json").content("{\"intent\":\"STORE_DATA\",\"latitude\":37.5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/chat/questions/research").header("Idempotency-Key", KEY)
                .contentType("application/json")
                .content("{\"intent\":\"STORE_DATA\",\"latitude\":99.0,\"longitude\":127.0}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("서비스가 이미 재검색한 의도라고 거절하면 409 CHAT-019를 돌려준다")
    void alreadyResearchedIsReturnedAsConflict() throws Exception {
        when(service.researchChat(1L, KEY, Intent.USER_DATA, null, null))
                .thenThrow(new ChatException(ChatErrorCode.ALREADY_RESEARCHED));

        mvc.perform(post("/chat/questions/research").header("Idempotency-Key", KEY)
                .contentType("application/json").content("{\"intent\":\"USER_DATA\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT-019"));
    }

    @Test
    @DisplayName("헤더가 없으면 null 키로 서비스에 넘기고, 서비스의 CHAT-001 거절을 400으로 돌려준다")
    void missingHeaderIsRejectedByService() throws Exception {
        when(service.researchChat(1L, null, Intent.GENERAL, null, null))
                .thenThrow(new ChatException(ChatErrorCode.INVALID_CHAT_REQUEST));

        mvc.perform(post("/chat/questions/research")
                .contentType("application/json").content("{\"intent\":\"GENERAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAT-001"));
    }

    private ChatAnswerTask task(CompletableFuture<ChatResponseDto> answer) {
        return new ChatAnswerTask(answer, () -> null);
    }
}