package com.ubot.chat.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatAttemptsService;
import com.ubot.conversation.repository.ConversationRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class ChatJobCancellationServiceTest {

	private final ChatJobRepository jobs = mock(ChatJobRepository.class);
	private final AnswerAttemptsHistoryRepository attempts = mock(AnswerAttemptsHistoryRepository.class);
	private final ConversationRepository conversations = mock(ConversationRepository.class);
	private final ChatAttemptsService attemptService = mock(ChatAttemptsService.class, CALLS_REAL_METHODS);
	private final ChatJobCancellationService service = new ChatJobCancellationService(jobs, attempts, conversations, attemptService);
	private final AnswerAttemptsHistory attempt = new AnswerAttemptsHistory(1L, 7L, "질문", 1,
			"a".repeat(64), LocalDateTime.now(), "", "");
	private final ChatJob job = ChatJob.queue(10L);

	@BeforeEach
	void setup() {
		when(jobs.findJobForLock(20L)).thenReturn(Optional.of(job));
		when(attempts.findAttemptForLock(10L)).thenReturn(Optional.of(attempt));
		when(conversations.existsByIdAndDeletedAtIsNull(7L)).thenReturn(true);
		ReflectionTestUtils.setField(attemptService, "maxAttempts", 3);
	}

	@ParameterizedTest
	@EnumSource(value = ChatJobStatus.class, names = {"QUEUED", "RUNNING"})
	void ownerCanCancelPendingJobAndLateCompletionLosesItsLease(ChatJobStatus status) {
		LocalDateTime startedAt = LocalDateTime.now();
		ReflectionTestUtils.setField(job, "status", status);
		ReflectionTestUtils.setField(job, "startedAt", startedAt);

		assertThat(service.cancel(20L, 1L, Optional.empty()).cancelled()).isTrue();
		assertThat(job.getStatus()).isEqualTo(ChatJobStatus.FAILED);
		assertThat(job.isRunningSince(startedAt)).isFalse();
		assertThat(job.getRequestContext()).isNull();
		assertThat(attempt.getErrorCode()).isEqualTo(ChatErrorCode.RESPONSE_CANCELLED);
		assertThat(attemptService.validateRetryAttempt(attempt)).contains(ChatErrorCode.RETRY_NOT_ALLOWED);
	}

	@Test
	void repeatedCancellationIsIdempotent() {
		assertThat(service.cancel(20L, 1L, Optional.empty()).cancelled()).isTrue();
		assertThat(service.cancel(20L, 1L, Optional.empty()).cancelled()).isTrue();
		verify(attemptService, times(1)).recordFailure(attempt, ChatErrorCode.RESPONSE_CANCELLED);
	}

	@ParameterizedTest
	@EnumSource(value = ChatJobStatus.class, names = {"SUCCEEDED", "FAILED"})
	void finishedResultIsPreserved(ChatJobStatus status) {
		ReflectionTestUtils.setField(job, "status", status);
		if (status == ChatJobStatus.SUCCEEDED) attempt.succeed();
		else attempt.fail(ChatErrorCode.NO_FAQ);

		assertThat(service.cancel(20L, 1L, Optional.empty()).cancelled()).isFalse();
		assertThat(job.getStatus()).isEqualTo(status);
		verify(attemptService, never()).recordFailure(any(), any());
	}

	@Test
	void guestCanCancelOnlyItsOwnSessionJob() {
		ReflectionTestUtils.setField(attempt, "userId", null);
		assertNotFound(null, Optional.of(8L));
		assertNotFound(null, Optional.empty());
		assertThat(service.cancel(20L, null, Optional.of(7L)).cancelled()).isTrue();
	}

	@Test
	void otherOwnersAndDeletedConversationsCannotCancel() {
		assertNotFound(2L, Optional.empty());
		assertNotFound(null, Optional.of(7L));
		when(conversations.existsByIdAndDeletedAtIsNull(7L)).thenReturn(false);
		assertNotFound(1L, Optional.empty());
		verify(attemptService, never()).recordFailure(any(), any());
	}

	private void assertNotFound(Long userId, Optional<Long> guestId) {
		assertThatThrownBy(() -> service.cancel(20L, userId, guestId))
				.isInstanceOfSatisfying(ChatException.class,
						error -> assertThat(error.getErrorCode()).isEqualTo(ChatErrorCode.JOB_NOT_FOUND));
	}
}
