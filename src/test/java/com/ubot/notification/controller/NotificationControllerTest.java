package com.ubot.notification.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.notification.dto.response.ReadAllResponseDto;
import com.ubot.notification.dto.response.UnreadCountResponseDto;
import com.ubot.notification.service.NotificationService;
import com.ubot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@DisplayName("알림 컨트롤러 테스트")
class NotificationControllerTest {
    private final NotificationService service = mock(NotificationService.class);
    private final CustomUserDetails principal = new CustomUserDetails(User.builder().id(1L).build());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == CustomUserDetails.class;
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory factory) {
                        return principal;
                    }
                }).build();
    }

    @Test
    @DisplayName("읽지 않은 알림 개수를 조회한다")
    void getsUnreadCount() throws Exception {
        when(service.getUnreadCount(1L)).thenReturn(new UnreadCountResponseDto(5));

        mvc.perform(get("/notifications/me/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.unreadCount").value(5));
    }

    @Test
    @DisplayName("내 알림을 전체 읽음 처리한다")
    void readsAllNotifications() throws Exception {
        when(service.readAllNotifications(1L)).thenReturn(new ReadAllResponseDto(3));

        mvc.perform(patch("/notifications/me/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.updatedCount").value(3));

        verify(service).readAllNotifications(1L);
    }
}