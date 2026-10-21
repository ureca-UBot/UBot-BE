package com.ubot.chat.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.worker.ChatJobWorker;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatAttemptsService;
import com.ubot.llm.exception.LlmErrorCode;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ChatJobCancellationServiceTest {

	private final ChatJobRepository jobs = mock(ChatJobRepository.class);
	private final AnswerAttemptsHistoryRepository attempts = mock(AnswerAttemptsHistoryRepository.class);
	private final ChatAttemptsService retryPolicy = mock(ChatAttemptsService.class);
	private final ChatJobWorker worker = mock(ChatJobWorker.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private final EntityManager entityManager = mock(EntityManager.class);
	private final AnswerAttemptsHistory first = attempt(1L, 7L, 40L, 1);
	private final ChatJob firstJob = job(11L, 1L);
	private ChatJobCancellationService service;

	@BeforeEach
	void setup() {
		when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		service = new ChatJobCancellationService(jobs, attempts, retryPolicy, worker, transactions, entityManager);
		when(jobs.findById(11L)).thenReturn(Optional.of(firstJob));
		when(jobs.findByAttemptId(1L)).thenReturn(Optional.of(firstJob));
		when(jobs.findJobForLock(11L)).thenReturn(Optional.of(firstJob));
		when(attempts.findById(1L)).thenReturn(Optional.of(first));
		when(attempts.findAttemptForLock(1L)).thenReturn(Optional.of(first));
		when(attempts.findInitialAttemptsForLock(7L, "key", 1)).thenReturn(Optional.of(first));
		when(attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(7L, "key"))
				.thenReturn(Optional.of(first));
		when(retryPolicy.validateRetryAttempt(any())).thenReturn(Optional.empty());
	}

	@Test
	void cancelsOnlyOwnedQueuedJobAndSignalsAfterCommit() {
		AtomicBoolean committed = new AtomicBoolean();
		doAnswer(call -> { committed.set(true); return null; }).when(transactions).commit(any());
		doAnswer(call -> { assertThat(committed).isTrue(); return null; }).when(worker).cancelJob(11L);
		var response = service.cancel(11L, 7L, Optional.empty());
		assertThat(response.cancelled()).isTrue();
		assertThat(firstJob.getStatus()).isEqualTo(ChatJobStatus.FAILED);
		assertThat(first.getErrorCode()).isEqualTo(ChatErrorCode.USER_CANCELLED);
		verify(worker).cancelJob(11L);
		verify(worker, times(1)).cancelJob(anyLong());
	}

	@Test
	void cancelsRunningJobAndRepeatedCancellationIsIdempotent() {
		ReflectionTestUtils.setField(firstJob, "status", ChatJobStatus.RUNNING);
		assertThat(service.cancel(11L, 7L, Optional.empty()).cancelled()).isTrue();
		assertThat(service.cancel(11L, 7L, Optional.empty()).cancelled()).isTrue();
		verify(worker, times(2)).cancelJob(11L);
	}

	@Test
	void anotherMemberCannotCancelOrDiscoverTheJob() {
		assertThatThrownBy(() -> service.cancel(11L, 8L, Optional.empty()))
				.isInstanceOfSatisfying(ChatException.class,
						error -> assertThat(error.getErrorCode()).isEqualTo(ChatErrorCode.JOB_NOT_FOUND));
		verifyNoInteractions(worker);
		verify(jobs, never()).findJobForLock(any());
		assertThat(first.getStatus()).isEqualTo("PENDING");
	}

	@Test
	void guestCannotCancelMemberJob() {
		assertThatThrownBy(() -> service.cancel(11L, null, Optional.of(40L))).isInstanceOf(ChatException.class);
		verifyNoInteractions(worker);
	}

	@Test
	void onlyOwningGuestConversationCanCancel() {
		ReflectionTestUtils.setField(first, "userId", null);
		when(attempts.findGuestInitialAttemptsForLock(40L, "key", 1)).thenReturn(Optional.of(first));
		when(attempts.findFirstByUserIdIsNullAndConversationIdAndIdempotencyKeyOrderByAttemptCountDesc(40L, "key"))
				.thenReturn(Optional.of(first));
		assertThatThrownBy(() -> service.cancel(11L, null, Optional.of(41L))).isInstanceOf(ChatException.class);
		assertThat(service.cancel(11L, null, Optional.of(40L)).cancelled()).isTrue();
	}

	@Test
	void completedAnswerWinsAndIsNeverOverwritten() {
		first.succeed();
		firstJob.succeed();
		assertThat(service.cancel(11L, 7L, Optional.empty()).cancelled()).isFalse();
		assertThat(first.getStatus()).isEqualTo("SUCCESS");
		verifyNoInteractions(worker);
	}

	@Test
	void refreshesPreLockStateWhenCompletionWonTheRace() {
		doAnswer(call -> { first.succeed(); firstJob.succeed(); return null; })
				.when(entityManager).refresh(first);
		assertThat(service.cancel(11L, 7L, Optional.empty()).cancelled()).isFalse();
		assertThat(first.getStatus()).isEqualTo("SUCCESS");
		verify(entityManager).refresh(firstJob);
		verifyNoInteractions(worker);
	}

	@Test
	void outputGuardRejectionAndItsReasonRemainUnchanged() {
		first.fail(LlmErrorCode.LLM_OUTPUT_REJECTED);
		firstJob.fail();
		when(retryPolicy.validateRetryAttempt(first)).thenReturn(Optional.of(ChatErrorCode.RETRY_NOT_ALLOWED));
		assertThat(service.cancel(11L, 7L, Optional.empty()).cancelled()).isFalse();
		assertThat(first.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED);
		verifyNoInteractions(worker);
	}

	@Test
	void cancelsRetryableFailureBetweenAttemptsSoNoNextAttemptIsAllowed() {
		first.fail(LlmErrorCode.LLM_TIMEOUT);
		firstJob.fail();
		assertThat(service.cancel(11L, 7L, Optional.empty()).cancelled()).isTrue();
		assertThat(first.getErrorCode()).isEqualTo(ChatErrorCode.USER_CANCELLED);
	}

	@Test
	void oldJobIdTargetsOnlyLatestRetryOfSameQuestion() {
		var retry = attempt(2L, 7L, 40L, 2);
		var retryJob = job(12L, 2L);
		when(attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(7L, "key"))
				.thenReturn(Optional.of(retry));
		when(jobs.findByAttemptId(2L)).thenReturn(Optional.of(retryJob));
		when(jobs.findJobForLock(12L)).thenReturn(Optional.of(retryJob));
		when(attempts.findAttemptForLock(2L)).thenReturn(Optional.of(retry));
		assertThat(service.cancel(11L, 7L, Optional.empty()).jobId()).isEqualTo(12L);
		assertThat(first.getStatus()).isEqualTo("PENDING");
		assertThat(retry.getErrorCode()).isEqualTo(ChatErrorCode.USER_CANCELLED);
		verify(worker).cancelJob(12L);
		verify(worker, never()).cancelJob(11L);
	}

	@Test
	void movingLatestRetryReleasesOldLocksAndRechecksInNewTransaction() {
		var retry = attempt(2L, 7L, 40L, 2);
		var retryJob = job(12L, 2L);
		when(attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(7L, "key"))
				.thenReturn(Optional.of(first), Optional.of(retry), Optional.of(retry), Optional.of(retry));
		when(jobs.findByAttemptId(2L)).thenReturn(Optional.of(retryJob));
		when(jobs.findJobForLock(12L)).thenReturn(Optional.of(retryJob));
		when(attempts.findAttemptForLock(2L)).thenReturn(Optional.of(retry));
		assertThat(service.cancel(11L, 7L, Optional.empty()).jobId()).isEqualTo(12L);
		verify(transactions, times(2)).commit(any());
		verify(worker, times(1)).cancelJob(12L);
	}

	@Test
	void failedCommitDoesNotSignalWorker() {
		doThrow(new IllegalStateException("commit failed")).when(transactions).commit(any());
		assertThatThrownBy(() -> service.cancel(11L, 7L, Optional.empty())).isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(worker);
	}

	private static AnswerAttemptsHistory attempt(long id, Long userId, long conversationId, int count) {
		return AnswerAttemptsHistory.builder().id(id).userId(userId).conversationId(conversationId)
				.question("질문").idempotencyKey("key").attemptCount(count).status("PENDING").build();
	}

	private static ChatJob job(long id, long attemptId) {
		ChatJob job = ChatJob.queue(attemptId);
		ReflectionTestUtils.setField(job, "id", id);
		return job;
	}
}
