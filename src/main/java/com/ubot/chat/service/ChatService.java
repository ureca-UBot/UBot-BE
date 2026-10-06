package com.ubot.chat.service;

import com.ubot.ai.dto.Location;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 회원·게스트의 질문과 재시도 요청을 검증하고 답변 시도를 만들어 실행을 요청합니다. */
@Slf4j
@Service
public class ChatService {
	private final ChatAttemptsService chatAttemptsService;
	private final ForbiddenWordFilterService forbiddenWordFilterService;
	private final ChatAnswerExecutor chatAnswerExecutor;

	public ChatService(
			ChatAttemptsService chatAttemptsService,
			ForbiddenWordFilterService forbiddenWordFilterService,
			ChatAnswerExecutor chatAnswerExecutor
	) {
		this.chatAttemptsService = chatAttemptsService;
		this.forbiddenWordFilterService = forbiddenWordFilterService;
		this.chatAnswerExecutor = chatAnswerExecutor;
	}

	/** 위치 없이 질문합니다. */
	public ChatAnswerTask createChat(Long userId, String question, String userIp) {
		return createChat(userId, question, null, userIp);
	}

	/** myLocation은 사용자가 내 위치 기준으로 다시 요청했을 때만 있습니다. */
	public ChatAnswerTask createChat(Long userId, String question, Location myLocation, String userIp) {
		validateQuestion(question);

		AnswerAttemptsHistory attempt = chatAttemptsService.createAnswerAttempt(userId, question);
		log.info("회원 답변 시도를 생성했습니다: 시도ID={}, 사용자ID={}", attempt.getId(), userId);
		return chatAnswerExecutor.startAnswerGeneration(attempt, myLocation, userIp);
	}

	/** 위치 없이 질문합니다. */
	public ChatAnswerTask createGuestChat(Long conversationId, String question, String userIp) {
		return createGuestChat(conversationId, question, null, userIp);
	}

	/** myLocation은 사용자가 내 위치 기준으로 다시 요청했을 때만 있습니다. */
	public ChatAnswerTask createGuestChat(Long conversationId, String question, Location myLocation, String userIp) {
		validateQuestion(question);

		AnswerAttemptsHistory attempt = chatAttemptsService.createGuestAnswerAttempt(conversationId, question);
		log.info("비회원 답변 시도를 생성했습니다: 시도ID={}, 대화ID={}", attempt.getId(), conversationId);
		return chatAnswerExecutor.startAnswerGeneration(attempt, myLocation, userIp);
	}

	// 시도 이력에 위치를 저장하지 않으므로 재시도는 회원·게스트 모두 위치 없이 진행합니다.
	public ChatAnswerTask retryChat(Long userId, String idempotencyKey, String userIp) {
		validateIdempotencyKey(idempotencyKey);

		AnswerAttemptsHistory attempt = chatAttemptsService.createRetryAttempt(userId, idempotencyKey);
		log.info("회원 답변 재시도를 생성했습니다: 시도ID={}, 사용자ID={}, 재시도횟수={}", attempt.getId(), userId, attempt.getAttemptCount());
		return chatAnswerExecutor.startAnswerGeneration(attempt, null, userIp);
	}

	public ChatAnswerTask retryGuestChat(Long conversationId, String idempotencyKey, String userIp) {
		validateIdempotencyKey(idempotencyKey);

		AnswerAttemptsHistory attempt = chatAttemptsService.createGuestRetryAttempt(conversationId, idempotencyKey);
		log.info("비회원 답변 재시도를 생성했습니다: 시도ID={}, 대화ID={}, 재시도횟수={}", attempt.getId(), conversationId, attempt.getAttemptCount());
		return chatAnswerExecutor.startAnswerGeneration(attempt, null, userIp);
	}

	private void validateQuestion(String question) {
		if (!StringUtils.hasText(question) || question.length() > 4000) {
			throw new ChatException(ChatErrorCode.INVALID_CHAT_REQUEST);
		}
		// 임베딩·FAQ 검색·LLM 호출 전에 금지어를 차단합니다.
		forbiddenWordFilterService.validateForbiddenWord(question);
	}

	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || !idempotencyKey.matches("[0-9a-f]{64}")) {
			throw new ChatException(ChatErrorCode.INVALID_CHAT_RETRY_REQUEST);
		}
	}
}
