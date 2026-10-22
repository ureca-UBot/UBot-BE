package com.ubot.chat.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.job.dto.response.ChatJobCancellationResponseDto;
import com.ubot.chat.job.service.ChatJobCancellationService;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.guest.session.GuestConversationService;
import com.ubot.user.entity.User;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class ChatJobCancellationControllerTest {

	private final ChatJobCancellationService cancellations = mock(ChatJobCancellationService.class);
	private final GuestConversationService guests = mock(GuestConversationService.class);
	private CustomUserDetails principal = new CustomUserDetails(User.builder().id(1L).build());
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new ChatJobCancellationController(cancellations, guests))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
					public boolean supportsParameter(MethodParameter parameter) {
						return parameter.getParameterType() == CustomUserDetails.class;
					}
					public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r,
							org.springframework.web.bind.support.WebDataBinderFactory f) {
						return principal;
					}
				}).build();
	}

	@Test
	void memberCancellationMatchesFrontendContract() throws Exception {
		when(cancellations.cancel(20L, 1L, Optional.empty()))
				.thenReturn(new ChatJobCancellationResponseDto(20L, true));
		mvc.perform(post("/chat/jobs/20/cancel"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.jobId").value(20))
				.andExpect(jsonPath("$.data.cancelled").value(true));
		verify(guests).claimConversation(any(), eq(1L));
	}

	@Test
	void guestCancellationUsesExistingSessionWithoutCreatingOne() throws Exception {
		principal = null;
		when(guests.findConversationId(any())).thenReturn(Optional.of(7L));
		when(cancellations.cancel(20L, null, Optional.of(7L)))
				.thenReturn(new ChatJobCancellationResponseDto(20L, false));
		mvc.perform(post("/chat/jobs/20/cancel"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.cancelled").value(false));
		verify(guests, never()).getOrCreateConversationId(any());
	}
}
