package com.ubot.chat.service;

import com.ubot.ai.dto.Location;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.chat.job.service.ChatSubmissionService;
import com.ubot.chat.job.service.ChatSubmissionService.ChatSubmission;
import com.ubot.chat.job.service.ChatSubmissionService.SubmissionType;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.faq.enums.Intent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 회원·게스트의 질문과 재시도 요청을 검증하고 답변 시도를 만들어 실행을 요청합니다.
 * 회원 접수는 질문 검사(형태소 분석 포함)보다 먼저 사용자 ID별 횟수를 셉니다. 게스트는 세션 질문 수로 제한합니다.
 */
@Slf4j
@Service
public class ChatService {
	private final ChatAttemptsService chatAttemptsService;
	private final ChatInputGuardService chatInputGuardService;
	private final ChatSubmissionService chatSubmissionService;
	private final IpRegionResolver ipRegionResolver;
	private final ChatRateLimitService chatRateLimitService;

	public ChatService(
			ChatAttemptsService chatAttemptsService,
			ChatInputGuardService chatInputGuardService,
			ChatSubmissionService chatSubmissionService,
			IpRegionResolver ipRegionResolver,
			ChatRateLimitService chatRateLimitService) {
		this.chatAttemptsService = chatAttemptsService;
		this.chatInputGuardService = chatInputGuardService;
		this.chatSubmissionService = chatSubmissionService;
		this.ipRegionResolver = ipRegionResolver;
		this.chatRateLimitService = chatRateLimitService;
	}

	/** myLocation은 사용자가 내 위치 기준으로 다시 요청했을 때만 있습니다. */
	public ChatSubmission createChat(Long userId, Long conversationId, String question, Location myLocation, String userIp) {
		chatRateLimitService.validateMemberRequest(userId);
		validateQuestion(question);
		return chatSubmissionService.submit(SubmissionType.QUESTION, true, () -> {
			AnswerAttemptsHistory attempt = chatAttemptsService.createAnswerAttempt(userId, conversationId, question);
			log.info("회원 답변 시도를 생성했습니다: 시도ID={}, 사용자ID={}, 대화ID={}", attempt.getId(), userId, conversationId);
			return attempt;
		}, context(myLocation, userIp));
	}

	/** myLocation은 사용자가 내 위치 기준으로 다시 요청했을 때만 있습니다. */
	public ChatSubmission createGuestChat(Long conversationId, String question, Location myLocation, String userIp) {
		validateQuestion(question);
		return chatSubmissionService.submit(SubmissionType.QUESTION, false, () -> {
			AnswerAttemptsHistory attempt = chatAttemptsService.createGuestAnswerAttempt(conversationId, question);
			log.info("비회원 답변 시도를 생성했습니다: 시도ID={}, 대화ID={}", attempt.getId(), conversationId);
			return attempt;
		}, context(myLocation, userIp));
	}

	// 시도 이력에 위치를 저장하지 않으므로 재시도는 회원·게스트 모두 위치 없이 진행합니다.
	public ChatSubmission retryChat(Long userId, String idempotencyKey, String userIp) {
		chatRateLimitService.validateMemberRequest(userId);
		validateIdempotencyKey(idempotencyKey, ChatErrorCode.INVALID_CHAT_RETRY_REQUEST);
		validateQuestion(chatAttemptsService.getLatestAnswerAttempt(userId, idempotencyKey).getQuestion());
		return chatSubmissionService.submit(SubmissionType.RETRY, true, () -> {
			AnswerAttemptsHistory attempt = chatAttemptsService.createRetryAttempt(userId, idempotencyKey);
			log.info("회원 답변 재시도를 생성했습니다: 시도ID={}, 사용자ID={}, 재시도횟수={}",
					attempt.getId(), userId, attempt.getAttemptCount());
			return attempt;
		}, context(null, userIp));
	}

	public ChatSubmission retryGuestChat(Long conversationId, String idempotencyKey, String userIp) {
		validateIdempotencyKey(idempotencyKey, ChatErrorCode.INVALID_CHAT_RETRY_REQUEST);
		validateQuestion(chatAttemptsService.getLatestGuestAnswerAttempt(conversationId, idempotencyKey).getQuestion());
		return chatSubmissionService.submit(SubmissionType.RETRY, false, () -> {
			AnswerAttemptsHistory attempt = chatAttemptsService.createGuestRetryAttempt(conversationId, idempotencyKey);
			log.info("비회원 답변 재시도를 생성했습니다: 시도ID={}, 대화ID={}, 재시도횟수={}",
					attempt.getId(), conversationId, attempt.getAttemptCount());
			return attempt;
		}, context(null, userIp));
	}

	/**
	 * 성공한 답변 또는 검색 근거가 부족한 원본 질문을 사용자가 고른 의도로 다시 검색합니다. 회원만 가능합니다.
	 * 본인 원본 질문을 다시 검사하고, 원문·기존 소유권·상태·잠금 검증은 보존합니다.
	 * myLocation은 사용자가 내 위치 기준으로 요청했을 때만 있습니다.
	 */
	public ChatSubmission researchChat(
			Long userId, String idempotencyKey, Intent intent, Location myLocation, String userIp) {
		chatRateLimitService.validateMemberRequest(userId);
		validateIdempotencyKey(idempotencyKey, ChatErrorCode.INVALID_CHAT_REQUEST);
		if (intent == null) {
			throw new ChatException(ChatErrorCode.INVALID_CHAT_REQUEST);
		}
		validateQuestion(chatAttemptsService.getLatestAnswerAttempt(userId, idempotencyKey).getQuestion());
		return chatSubmissionService.submit(SubmissionType.RESEARCH, true, () -> {
			AnswerAttemptsHistory attempt = chatAttemptsService.createResearchAttempt(userId, idempotencyKey, intent);
			log.info("회원 의도 재검색 시도를 생성했습니다: 시도ID={}, 원본시도ID={}, 사용자ID={}, 의도={}",
					attempt.getId(), attempt.getSourceAttemptId(), userId, intent);
			return attempt;
		}, context(myLocation, userIp));
	}

	// Worker는 요청 객체를 못 보므로 좌표·접속 지역을 작업에 담아 넘김
	private ChatRequestContext context(Location myLocation, String userIp) {
		return ChatRequestContext.of(myLocation, ipRegionResolver.resolve(userIp).orElse(null));
	}

	private void validateQuestion(String question) {
		if (!StringUtils.hasText(question) || question.length() > 4000) {
			throw new ChatException(ChatErrorCode.INVALID_CHAT_REQUEST);
		}
		// 원문은 기록에 보존하고, 처리할 요청이 남는지는 접수 전에 확인합니다.
		chatInputGuardService.prepareSubmittedQuestion(question);
	}

	private void validateIdempotencyKey(String idempotencyKey, ChatErrorCode invalidCode) {
		if (idempotencyKey == null || !idempotencyKey.matches("[0-9a-f]{64}")) {
			throw new ChatException(invalidCode);
		}
	}
}
