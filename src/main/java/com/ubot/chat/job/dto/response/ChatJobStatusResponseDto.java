package com.ubot.chat.job.dto.response;

import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.common.ErrorCode;

// QUEUED·RUNNING이면 결과 없이 retryable=false. FAILED의 retryable은 기존 재시도 정책을 따름
public record ChatJobStatusResponseDto(
		Long jobId,
		ChatJobStatus status,
		String answer,
		ChatStoreDto store,
		String errorCode,
		String errorMessage,
		boolean retryable,
		String idempotencyKey,
		int attemptCount
) {
	public static ChatJobStatusResponseDto pending(ChatJob job, AnswerAttemptsHistory attempt) {
		return of(job, attempt, null, null, null, false);
	}

	public static ChatJobStatusResponseDto succeeded(
			ChatJob job, AnswerAttemptsHistory attempt, String answer, ChatStoreDto store) {
		return of(job, attempt, answer, store, null, false);
	}

	public static ChatJobStatusResponseDto failed(
			ChatJob job, AnswerAttemptsHistory attempt, ErrorCode errorCode, boolean retryable) {
		return of(job, attempt, null, null, errorCode, retryable);
	}

	private static ChatJobStatusResponseDto of(
			ChatJob job,
			AnswerAttemptsHistory attempt,
			String answer,
			ChatStoreDto store,
			ErrorCode errorCode,
			boolean retryable) {
		return new ChatJobStatusResponseDto(
				job.getId(),
				job.getStatus(),
				answer,
				store,
				errorCode == null ? null : errorCode.getCode(),
				errorCode == null ? null : errorCode.getMessage(),
				retryable,
				attempt.getIdempotencyKey(),
				attempt.getAttemptCount());
	}
}
