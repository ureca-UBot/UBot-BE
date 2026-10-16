package com.ubot.chat.controller;

import com.ubot.conversation.service.ConversationService;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.ai.dto.Location;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.dto.response.ChatJobSubmissionResponseDto;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.service.ChatJobQueryService;
import com.ubot.chat.job.service.ChatSubmissionService.ChatSubmission;
import com.ubot.chat.service.ChatService;
import com.ubot.chat.util.ClientIpResolver;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.faq.enums.Intent;
import com.ubot.guest.session.GuestConversationService;
import com.ubot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@DisplayName("의도 재검색 컨트롤러 테스트")
class ChatControllerResearchTest {
    private static final String KEY = "a".repeat(64);
    private static final long JOB_ID = 12345L;

    ChatService service = mock(ChatService.class);
    ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    GuestConversationService guestConversationService = mock(GuestConversationService.class);
    CustomUserDetails principal = new CustomUserDetails(User.builder().id(1L).build());
    MockMvc mvc;

    @BeforeEach
    void setup() {
        var controller = new ChatController(service, mock(ChatJobQueryService.class), clientIpResolver,
                guestConversationService, mock(ConversationService.class));
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
    @DisplayName("회원은 헤더의 키와 본문의 의도로 재검색을 접수하고, 202로 작업 ID를 받는다")
    void memberResearchPassesKeyAndIntent() throws Exception {
        when(service.researchChat(1L, KEY, Intent.STORE_DATA, null, null)).thenReturn(submission());

        mvc.perform(post("/chat/questions/research").header("Idempotency-Key", KEY)
                .contentType("application/json").content("{\"intent\":\"STORE_DATA\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").value(JOB_ID))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(service).researchChat(1L, KEY, Intent.STORE_DATA, null, null);
        verify(guestConversationService).claimConversation(any(), eq(1L));
    }

    @Test
    @DisplayName("좌표를 함께 보내면 Location으로 서비스에 전달한다")
    void coordinatesAreForwardedAsLocation() throws Exception {
        when(service.researchChat(1L, KEY, Intent.STORE_DATA, new Location(37.5, 127.0), null))
                .thenReturn(submission());

        mvc.perform(post("/chat/questions/research").header("Idempotency-Key", KEY)
                .contentType("application/json")
                .content("{\"intent\":\"STORE_DATA\",\"latitude\":37.5,\"longitude\":127.0}"))
                .andExpect(status().isAccepted());

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

    private ChatSubmission submission() {
        return new ChatSubmission(new ChatJobSubmissionResponseDto(JOB_ID, ChatJobStatus.QUEUED, null, KEY, 1), 5L);
    }
}
