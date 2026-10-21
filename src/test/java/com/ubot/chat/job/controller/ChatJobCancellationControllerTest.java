package com.ubot.chat.job.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.job.dto.response.ChatJobCancellationResponseDto;
import com.ubot.chat.job.service.ChatJobCancellationService;
import com.ubot.guest.session.GuestConversationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatJobCancellationControllerTest {

	private final ChatJobCancellationService cancellation = mock(ChatJobCancellationService.class);
	private final GuestConversationService guests = mock(GuestConversationService.class);
	private final HttpServletRequest request = mock(HttpServletRequest.class);
	private final ChatJobCancellationController controller = new ChatJobCancellationController(cancellation, guests);

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	private MockMvc http() {
		return MockMvcBuilders.standaloneSetup(controller)
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.build();
	}

	@Test
	void httpMemberCancellationReturnsFrontendContractAndUsesAuthenticatedOwner() throws Exception {
		CustomUserDetails user = mock(CustomUserDetails.class);
		when(user.getUserId()).thenReturn(7L);
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null));
		when(cancellation.cancel(11L, 7L, Optional.empty()))
				.thenReturn(new ChatJobCancellationResponseDto(11L, true));
		http().perform(post("/chat/jobs/11/cancel"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.jobId").value(11))
				.andExpect(jsonPath("$.data.cancelled").value(true));
		verify(cancellation).cancel(11L, 7L, Optional.empty());
	}

	@Test
	void httpGuestCancellationUsesServerSessionRatherThanBodyOwner() throws Exception {
		when(guests.findConversationId(any())).thenReturn(Optional.of(40L));
		when(cancellation.cancel(11L, null, Optional.of(40L)))
				.thenReturn(new ChatJobCancellationResponseDto(11L, true));
		http().perform(post("/chat/jobs/11/cancel")
						.contentType("application/json").content("{\"userId\":7,\"conversationId\":999}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.cancelled").value(true));
		verify(cancellation).cancel(11L, null, Optional.of(40L));
	}

	@Test
	void httpCompletionWonIsReportedWithoutPretendingToCancel() throws Exception {
		when(guests.findConversationId(any())).thenReturn(Optional.of(40L));
		when(cancellation.cancel(11L, null, Optional.of(40L)))
				.thenReturn(new ChatJobCancellationResponseDto(11L, false));
		http().perform(post("/chat/jobs/11/cancel"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.cancelled").value(false));
	}

	@Test
	void memberUsesAuthenticatedIdentityRatherThanRequestBody() {
		CustomUserDetails user = mock(CustomUserDetails.class);
		when(user.getUserId()).thenReturn(7L);
		when(cancellation.cancel(11L, 7L, Optional.empty()))
				.thenReturn(new ChatJobCancellationResponseDto(11L, true));
		assertThat(controller.cancel(user, 11L, request).data().cancelled()).isTrue();
		verify(guests).claimConversation(request, 7L);
		verify(cancellation).cancel(11L, 7L, Optional.empty());
	}

	@Test
	void guestUsesOnlySessionConversation() {
		when(guests.findConversationId(request)).thenReturn(Optional.of(40L));
		when(cancellation.cancel(11L, null, Optional.of(40L)))
				.thenReturn(new ChatJobCancellationResponseDto(11L, true));
		assertThat(controller.cancel(null, 11L, request).data().jobId()).isEqualTo(11L);
		verify(cancellation).cancel(11L, null, Optional.of(40L));
	}

	@Test
	void missingGuestIdentityIsNotReplacedWithClaimedIdentity() {
		when(guests.findConversationId(request)).thenReturn(Optional.empty());
		when(cancellation.cancel(11L, null, Optional.empty()))
				.thenReturn(new ChatJobCancellationResponseDto(11L, false));
		assertThat(controller.cancel(null, 11L, request).data().cancelled()).isFalse();
		verify(cancellation).cancel(11L, null, Optional.empty());
	}
}
