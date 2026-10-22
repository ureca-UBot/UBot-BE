package com.ubot.chat.job.service;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.dto.response.ChatJobCancellationResponseDto;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatAttemptsService;
import com.ubot.conversation.repository.ConversationRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatJobCancellationService {

	private final ChatJobRepository jobs;
	private final AnswerAttemptsHistoryRepository attempts;
	private final ConversationRepository conversations;
	private final ChatAttemptsService chatAttemptsService;

	// 완료 처리와 같은 순서로 작업·시도를 잠급니다. 먼저 확정된 완료 결과는 보존합니다.
	@Transactional
	public ChatJobCancellationResponseDto cancel(Long jobId, Long userId, Optional<Long> guestConversationId) {
		ChatJob job = jobs.findJobForLock(jobId)
				.orElseThrow(() -> new ChatException(ChatErrorCode.JOB_NOT_FOUND));
		AnswerAttemptsHistory attempt = attempts.findAttemptForLock(job.getAttemptId())
				.filter(found -> isOwner(found, userId, guestConversationId))
				.orElseThrow(() -> new ChatException(ChatErrorCode.JOB_NOT_FOUND));
		if (job.getStatus() == ChatJobStatus.SUCCEEDED || job.getStatus() == ChatJobStatus.FAILED) {
			return new ChatJobCancellationResponseDto(jobId,
					attempt.getErrorCode() == ChatErrorCode.RESPONSE_CANCELLED);
		}
		if (!chatAttemptsService.recordFailure(attempt, ChatErrorCode.RESPONSE_CANCELLED)) {
			return new ChatJobCancellationResponseDto(jobId, false);
		}
		// Worker의 실행 권한도 끝냅니다. 늦게 도착한 결과는 완료 처리에서 거절되며 heartbeat에서 실행을 중단합니다.
		job.fail();
		return new ChatJobCancellationResponseDto(jobId, true);
	}

	private boolean isOwner(AnswerAttemptsHistory attempt, Long userId, Optional<Long> guestConversationId) {
		boolean owner = userId != null
				? userId.equals(attempt.getUserId())
				: attempt.getUserId() == null && guestConversationId
						.filter(id -> Objects.equals(id, attempt.getConversationId())).isPresent();
		return owner && (attempt.getConversationId() == null
				|| conversations.existsByIdAndDeletedAtIsNull(attempt.getConversationId()));
	}
}
