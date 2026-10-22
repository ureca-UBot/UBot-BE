package com.ubot.chat.job.dto.response;

import com.ubot.ai.dto.UserDataCards;
import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.common.ErrorCode;
import java.time.Instant;
import com.ubot.ai.progress.AnswerProgress.Phase;

// QUEUED·RUNNING이면 결과 없이 retryable=false. FAILED의 retryable은 기존 재시도 정책을 따름
// userData는 성공한 가입 정보 답변에 쓰인 요금제·사용량·청구서 카드이고, 없으면 null
public record ChatJobStatusResponseDto(
		Long jobId,
		ChatJobStatus status,
		String answer,
		ChatStoreDto store,
		UserDataCards userData,
		String errorCode,
		String errorMessage,
		boolean retryable,
		String idempotencyKey,
		int attemptCount,
		Phase phase,
		Instant phaseUpdatedAt,
		Instant deadlineAt,
		Instant serverTime
) {
	public ChatJobStatusResponseDto(Long jobId, ChatJobStatus status, String answer, ChatStoreDto store,
			String errorCode, String errorMessage, boolean retryable, String idempotencyKey, int attemptCount) {
		this(jobId, status, answer, store, null, errorCode, errorMessage, retryable, idempotencyKey, attemptCount,
				null, null, null, Instant.now());
	}

	public static ChatJobStatusResponseDto pending(ChatJob job, AnswerAttemptsHistory attempt) {
		return of(job, attempt, null, null, null, null, false);
	}

	public static ChatJobStatusResponseDto succeeded(
			ChatJob job, AnswerAttemptsHistory attempt, String answer, ChatStoreDto store) {
		return succeeded(job, attempt, answer, store, null);
	}

	public static ChatJobStatusResponseDto succeeded(
			ChatJob job, AnswerAttemptsHistory attempt, String answer, ChatStoreDto store, UserDataCards userData) {
		return of(job, attempt, answer, store, userData, null, false);
	}

	public static ChatJobStatusResponseDto failed(
			ChatJob job, AnswerAttemptsHistory attempt, ErrorCode errorCode, boolean retryable) {
		return of(job, attempt, null, null, null, errorCode, retryable);
	}

	private static ChatJobStatusResponseDto of(
			ChatJob job,
			AnswerAttemptsHistory attempt,
			String answer,
			ChatStoreDto store,
			UserDataCards userData,
			ErrorCode errorCode,
			boolean retryable) {
		return new ChatJobStatusResponseDto(
				job.getId(),
				job.getStatus(),
				answer,
				store,
				userData,
				errorCode == null ? null : errorCode.getCode(),
				errorCode == null ? null : errorCode.getMessage(),
				retryable,
				attempt.getIdempotencyKey(),
				attempt.getAttemptCount(), job.getPhase(), utc(job.getPhaseUpdatedAt()), utc(job.getDeadlineAt()), Instant.now());
	}

	private static Instant utc(java.time.LocalDateTime time) {
		return time == null ? null : time.atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
	}
}
