package com.ubot.chat.job.service;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.dto.ChatJobResult;
import com.ubot.chat.job.dto.response.ChatJobStatusResponseDto;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.stream.ChatJobResultStore;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.service.ChatAttemptsService;
import com.ubot.conversation.repository.ConversationRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 본인 작업만 보여 줌(회원은 JWT 사용자, 게스트는 세션 대화). 남의 작업과 없는 작업은 같은 CHAT-020으로 거절함
@Slf4j
@Service
public class ChatJobQueryService {
	private final ChatJobRepository chatJobRepository;
	private final AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository;
	private final QuestionLogRepository questionLogRepository;
	private final ChatAttemptsService chatAttemptsService;
	private final ChatJobResultStore resultStore;
	private final ConversationRepository conversationRepository;

	public ChatJobQueryService(
			ChatJobRepository chatJobRepository,
			AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository,
			QuestionLogRepository questionLogRepository,
			ChatAttemptsService chatAttemptsService,
			ChatJobResultStore resultStore,
			ConversationRepository conversationRepository) {
		this.chatJobRepository = chatJobRepository;
		this.answerAttemptsHistoryRepository = answerAttemptsHistoryRepository;
		this.questionLogRepository = questionLogRepository;
		this.chatAttemptsService = chatAttemptsService;
		this.resultStore = resultStore;
		this.conversationRepository = conversationRepository;
	}

	@Transactional(readOnly = true)
	public ChatJobStatusResponseDto getStatus(Long jobId, Long userId, Optional<Long> guestConversationId) {
		ChatJob job = chatJobRepository.findById(jobId)
				.orElseThrow(() -> new ChatException(ChatErrorCode.JOB_NOT_FOUND));
		AnswerAttemptsHistory attempt = answerAttemptsHistoryRepository.findById(job.getAttemptId())
				.filter(found -> isOwner(found, userId, guestConversationId))
				.orElseThrow(() -> new ChatException(ChatErrorCode.JOB_NOT_FOUND));
		return switch (job.getStatus()) {
			case SUCCEEDED -> succeeded(job, attempt);
			case FAILED -> failed(job, attempt);
			case QUEUED, RUNNING -> ChatJobStatusResponseDto.pending(job, attempt);
		};
	}

	private ChatJobStatusResponseDto succeeded(ChatJob job, AnswerAttemptsHistory attempt) {
		// 새 작업은 DB 결과를 씀. Redis는 이전 버전 작업용
		Optional<ChatJobResult> result = Optional.ofNullable(job.getResult()).or(() -> findResult(job.getId()));
		QuestionLog questionLog = result.flatMap(found -> questionLogRepository.findById(found.questionLogId()))
				.orElseThrow(() -> new ChatException(ChatErrorCode.STORAGE_UNAVAILABLE));
		return ChatJobStatusResponseDto.succeeded(job, attempt,
				questionLog.getAnswer(),
				result.map(ChatJobResult::store).orElse(null),
				result.map(ChatJobResult::userData).orElse(null),
				result.map(ChatJobResult::productData).orElse(null));
	}

	// V33 이전 작업 호환용
	private Optional<ChatJobResult> findResult(long jobId) {
		try {
			return resultStore.find(jobId);
		} catch (RuntimeException exception) {
			log.warn("이전 버전 작업의 결과 캐시를 읽지 못했습니다: 작업ID={}", jobId, exception);
			return Optional.empty();
		}
	}

	// 시도가 실패로 남지 않았으면(작업만 닫힘) 원인을 몰라 내부 오류로 보여 줌
	private ChatJobStatusResponseDto failed(ChatJob job, AnswerAttemptsHistory attempt) {
		if (!"FAIL".equals(attempt.getStatus()) || attempt.getErrorCode() == null) {
			return ChatJobStatusResponseDto.failed(job, attempt, ChatErrorCode.INTERNAL_ERROR, false);
		}
		AnswerAttemptsHistory latest = (attempt.getUserId() == null
				? answerAttemptsHistoryRepository.findFirstByUserIdIsNullAndConversationIdAndIdempotencyKeyOrderByAttemptCountDesc(
						attempt.getConversationId(), attempt.getIdempotencyKey())
				: answerAttemptsHistoryRepository.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(
						attempt.getUserId(), attempt.getIdempotencyKey())).orElse(attempt);
		boolean retryable = Objects.equals(latest.getId(), attempt.getId())
				&& chatAttemptsService.validateRetryAttempt(latest).isEmpty();
		return ChatJobStatusResponseDto.failed(job, attempt, attempt.getErrorCode(), retryable);
	}

	boolean isOwner(AnswerAttemptsHistory attempt, Long userId, Optional<Long> guestConversationId) {
		boolean owner;
		if (userId != null) {
			owner = userId.equals(attempt.getUserId());
		} else {
			owner = attempt.getUserId() == null
					&& guestConversationId.filter(id -> Objects.equals(id, attempt.getConversationId())).isPresent();
		}
		return owner && (attempt.getConversationId() == null
				|| conversationRepository.existsByIdAndDeletedAtIsNull(attempt.getConversationId()));
	}
}
