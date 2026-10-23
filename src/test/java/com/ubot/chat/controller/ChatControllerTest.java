package com.ubot.chat.controller;

import com.ubot.ai.dto.Location;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.dto.response.ChatJobStatusResponseDto;
import com.ubot.chat.job.dto.response.ChatJobSubmissionResponseDto;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.service.ChatJobQueryService;
import com.ubot.chat.job.service.ChatJobReadExecutor;
import com.ubot.chat.job.service.ChatSubmissionService.ChatSubmission;
import com.ubot.chat.service.ChatService;
import com.ubot.chat.util.ClientIpResolver;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.conversation.entity.Conversation;
import com.ubot.conversation.service.ConversationService;
import com.ubot.guest.session.GuestConversationService;
import com.ubot.user.entity.User;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTest {
	static final long JOB_ID = 12345L;
	static final UUID CONVERSATION_PUBLIC_ID = UUID.fromString("a927bf8e-4ab4-4d3b-814b-dde3c0e62aac");
	static final String KEY = "a".repeat(64);

	ChatService service = mock(ChatService.class);
	ChatJobQueryService queryService = mock(ChatJobQueryService.class);
	ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
	GuestConversationService guestConversationService = mock(GuestConversationService.class);
	ConversationService conversationService = mock(ConversationService.class);
	CustomUserDetails principal = new CustomUserDetails(User.builder().id(1L).build());
	MockMvc mvc;
	ChatJobReadExecutor reads = new ChatJobReadExecutor(Duration.ofMillis(1500), 8);
	@AfterEach void closeReads() { reads.close(); }

	@BeforeEach void setup() {
		when(conversationService.getOrCreateMemberConversation(eq(1L), any())).thenReturn(Conversation.createMember(1L));
		when(conversationService.getPublicId(5L)).thenReturn(CONVERSATION_PUBLIC_ID);
		var controller = new ChatController(
				service, queryService, clientIpResolver, guestConversationService, conversationService, reads);
		mvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
					public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == CustomUserDetails.class; }
					public Object resolveArgument(MethodParameter p, ModelAndViewContainer c, NativeWebRequest r,
							org.springframework.web.bind.support.WebDataBinderFactory f) { return principal; }
				}).build();
	}

	@Test void questionIsAcceptedWithJobIdWithoutWaitingForAnswer() throws Exception {
		when(service.createChat(1L, null, "질문", null, null)).thenReturn(submission(1));

		mvc.perform(post("/chat/questions").contentType("application/json")
						.content("{\"question\":\"질문\",\"userId\":999}"))
				.andExpect(request().asyncNotStarted())
				.andExpect(status().isAccepted())
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data.jobId").value(JOB_ID))
				.andExpect(jsonPath("$.data.status").value("QUEUED"))
				.andExpect(jsonPath("$.data.conversationId").value(CONVERSATION_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.data.idempotencyKey").value(KEY))
				.andExpect(jsonPath("$.data.attemptCount").value(1))
				.andExpect(jsonPath("$.data.answer").doesNotExist());
		verify(service).createChat(1L, null, "질문", null, null);
		verify(guestConversationService).claimConversation(any(), eq(1L));
		verify(guestConversationService, never()).getOrCreateConversationId(any());
	}

	@Test void guestQuestionUsesSessionConversationInsteadOfUser() throws Exception {
		principal = null;
		when(guestConversationService.getOrCreateConversationId(any())).thenReturn(7L);
		when(service.createGuestChat(7L, "질문", null, null)).thenReturn(submission(1));

		mvc.perform(post("/chat/questions").contentType("application/json").content("{\"question\":\"질문\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.jobId").value(JOB_ID));

		verify(service).createGuestChat(7L, "질문", null, null);
		verify(service, never()).createChat(any(), any(), any(), any(), any());
	}

	@Test void guestRetryUsesExistingConversationAndNeverCreatesOne() throws Exception {
		principal = null;
		when(guestConversationService.findConversationId(any())).thenReturn(Optional.of(7L));
		when(service.retryGuestChat(7L, KEY, null)).thenReturn(submission(2));

		mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", KEY))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.attemptCount").value(2));

		verify(service).retryGuestChat(7L, KEY, null);
		verify(guestConversationService, never()).getOrCreateConversationId(any());
	}

	@Test void guestRetryWithoutConversationIsRejectedBeforeService() throws Exception {
		principal = null;
		when(guestConversationService.findConversationId(any())).thenReturn(Optional.empty());

		mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", KEY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CHAT-003"));
		verifyNoInteractions(service);
		verify(guestConversationService, never()).getOrCreateConversationId(any());
	}

	@Test void retryUsesKeyWithoutSessionOrQuestionIdsAndOmitsConversation() throws Exception {
		when(service.retryChat(1L, KEY, null)).thenReturn(submission(2));

		mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", KEY))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.jobId").value(JOB_ID))
				.andExpect(jsonPath("$.data.idempotencyKey").value(KEY))
				.andExpect(jsonPath("$.data.conversationId").doesNotExist());
		verify(service).retryChat(1L, KEY, null);
	}

	@Test void invalidRetryRequestUsesCommonErrorResponse() throws Exception {
		when(service.retryChat(1L, "invalid-key", null))
				.thenThrow(new ChatException(ChatErrorCode.INVALID_CHAT_RETRY_REQUEST));

		mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", "invalid-key"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("CHAT-011"))
				.andExpect(jsonPath("$.data").doesNotExist());
		verify(service).retryChat(1L, "invalid-key", null);
	}

	@Test void blankQuestionIsRejectedBeforeService() throws Exception {
		mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\" \"}")).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test void locationIsPassedToService() throws Exception {
		when(service.createChat(1L, null, "근처 매장", new Location(37.498, 127.028), null)).thenReturn(submission(1));

		mvc.perform(post("/chat/questions").contentType("application/json")
				.content("{\"question\":\"근처 매장\",\"latitude\":37.498,\"longitude\":127.028,\"useMyLocation\":true}"))
				.andExpect(status().isAccepted());
		verify(service).createChat(1L, null, "근처 매장", new Location(37.498, 127.028), null);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"question\":\"질문\",\"latitude\":37.498}",
			"{\"question\":\"질문\",\"longitude\":127.028}",
			"{\"question\":\"질문\",\"latitude\":91,\"longitude\":127.028}",
			"{\"question\":\"질문\",\"latitude\":37.498,\"longitude\":181}",
			"{\"question\":\"질문\",\"useMyLocation\":true}"
	})
	void invalidLocationIsRejectedBeforeService(String body) throws Exception {
		mvc.perform(post("/chat/questions").contentType("application/json").content(body))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test void memberJobStatusIsLookedUpByUser() throws Exception {
		when(queryService.getStatus(JOB_ID, 1L, Optional.empty())).thenReturn(new ChatJobStatusResponseDto(
				JOB_ID, ChatJobStatus.SUCCEEDED, "완성된 답변", null, null, null, false, KEY, 1));

		mvc.perform(get("/chat/jobs/{jobId}", JOB_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.jobId").value(JOB_ID))
				.andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.data.answer").value("완성된 답변"))
				.andExpect(jsonPath("$.data.store").doesNotExist())
				.andExpect(jsonPath("$.data.retryable").value(false));
		verify(guestConversationService, never()).findConversationId(any());
	}

	@Test void guestJobStatusIsLookedUpBySessionConversation() throws Exception {
		principal = null;
		when(guestConversationService.findConversationId(any())).thenReturn(Optional.of(7L));
		when(queryService.getStatus(JOB_ID, null, Optional.of(7L))).thenReturn(new ChatJobStatusResponseDto(
				JOB_ID, ChatJobStatus.FAILED, null, null, "CHAT-013", "정확한 답변을 찾지 못했습니다.", true, KEY, 1));

		mvc.perform(get("/chat/jobs/{jobId}", JOB_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("FAILED"))
				.andExpect(jsonPath("$.data.errorCode").value("CHAT-013"))
				.andExpect(jsonPath("$.data.retryable").value(true));
		verify(guestConversationService, never()).getOrCreateConversationId(any());
	}

	@Test void someoneElsesJobIsNotFound() throws Exception {
		when(queryService.getStatus(JOB_ID, 1L, Optional.empty()))
				.thenThrow(new ChatException(ChatErrorCode.JOB_NOT_FOUND));

		mvc.perform(get("/chat/jobs/{jobId}", JOB_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CHAT-020"));
	}

	@Test void malformedJobIdIsRejected() throws Exception {
		mvc.perform(get("/chat/jobs/{jobId}", "not-a-uuid"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(queryService);
	}

	@Test void sessionApiNoLongerExists() throws Exception {
		// 팀의 공통 예외 처리기는 없는 경로도 공통 오류로 변환하므로 핸들러 부재를 확인합니다.
		assertThat(mvc.perform(post("/chat/sessions")).andReturn().getHandler()).isNull();
		assertThat(mvc.perform(post("/chat/sessions/1/questions")).andReturn().getHandler()).isNull();
		verifyNoInteractions(service);
	}

	private ChatSubmission submission(int attemptCount) {
		return new ChatSubmission(
				new ChatJobSubmissionResponseDto(JOB_ID, ChatJobStatus.QUEUED, null, KEY, attemptCount), 5L);
	}
}
