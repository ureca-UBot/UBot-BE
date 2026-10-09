package com.ubot.chat.service;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.common.ErrorCode;
import com.ubot.conversation.entity.Conversation;
import com.ubot.conversation.repository.ConversationRepository;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.entity.FaqLog;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.guest.entity.GuestChatSettings;
import com.ubot.guest.repository.GuestChatSettingsRepository;
import com.ubot.llm.service.LlmService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;

/** 시도 횟수·멱등키와 JPA 기록 저장을 담당합니다. */
@Service
@Slf4j
public class ChatAttemptsService {
	@Value("${CHAT_MAX_ATTEMPTS:3}")
	private int maxAttempts;

	private final AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository;
	private final FaqLogRepository faqLogRepository;
	private final QuestionLogService questionLogService;
	private final FaqRepository faqRepository;
	private final ConversationRepository conversationRepository;
	private final GuestChatSettingsRepository guestChatSettingsRepository;
	private final TransactionTemplate transactionTemplate;

	// AI_MODE에 따라 답변을 만드는 모델이 달라지므로, 설정을 직접 읽지 않고 LLM 모듈에서 이름을 받습니다.
	private final String llmModel;

	// AI_MODE에 따라 임베딩 모델이 달라지므로, 같은 방식으로 임베딩 모듈에서 이름을 받습니다.
	private final String embeddingModel;

	public ChatAttemptsService(
			AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository,
			QuestionLogService questionLogService,
			FaqLogRepository faqLogRepository,
			FaqRepository faqRepository,
			ConversationRepository conversationRepository,
			GuestChatSettingsRepository guestChatSettingsRepository,
			LlmService llmService,
			EmbeddingService embeddingService,
			PlatformTransactionManager transactionManager) {
		this.answerAttemptsHistoryRepository = answerAttemptsHistoryRepository;
		this.questionLogService = questionLogService;
		this.faqLogRepository = faqLogRepository;
		this.faqRepository = faqRepository;
		this.conversationRepository = conversationRepository;
		this.guestChatSettingsRepository = guestChatSettingsRepository;
		this.llmModel = llmService.getModelName();
		this.embeddingModel = embeddingService.getModelName();
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		// 기록 저장 트랜잭션만 열고, 모델 호출 중에는 DB 연결이나 행 잠금을 유지하지 않습니다.
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public AnswerAttemptsHistory createAnswerAttempt(Long userId, String question) {
		String input = question.strip();
		LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
		String idempotencyKey = createIdempotencyKey(userId, input, createdAt);
		return transactionTemplate
				.execute(transactionStatus -> answerAttemptsHistoryRepository.saveAndFlush(new AnswerAttemptsHistory(
						userId, input, 1, idempotencyKey, createdAt, llmModel, embeddingModel)));
	}

	public AnswerAttemptsHistory createGuestAnswerAttempt(Long conversationId, String question) {
		String input = question.strip();
		LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
		String idempotencyKey = createGuestIdempotencyKey(conversationId, input, createdAt);
		return transactionTemplate.execute(transactionStatus -> {
			validateGuestQuestionLimit(conversationId, idempotencyKey);
			return answerAttemptsHistoryRepository.saveAndFlush(new AnswerAttemptsHistory(
					null, conversationId, input, 1, idempotencyKey, createdAt, llmModel, embeddingModel));
		});
	}

	public AnswerAttemptsHistory createRetryAttempt(Long userId, String idempotencyKey) {
		return createRetryAttempt(userId, null, idempotencyKey);
	}

	public AnswerAttemptsHistory createGuestRetryAttempt(Long conversationId, String idempotencyKey) {
		return createRetryAttempt(null, conversationId, idempotencyKey);
	}

	/** 성공한 답변(idempotencyKey)에 대해, 로그인 사용자가 고른 intent로 재검색하는 새 attempt를 만듭니다. */
	public AnswerAttemptsHistory createResearchAttempt(Long userId, String idempotencyKey, Intent intent) {
		return transactionTemplate.execute(transactionStatus -> {
			AnswerAttemptsHistory latest = answerAttemptsHistoryRepository
					.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(userId, idempotencyKey)
					.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));

			AnswerAttemptsHistory source = answerAttemptsHistoryRepository
					.findAttemptForLock(latest.getId())
					.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));

			if (!"SUCCESS".equals(source.getStatus()) || source.isResearch()) {
				throw new ChatException(ChatErrorCode.RESEARCH_NOT_ALLOWED);
			}
			if (answerAttemptsHistoryRepository.existsBySourceAttemptIdAndIntent(source.getId(), intent)) {
				throw new ChatException(ChatErrorCode.ALREADY_RESEARCHED);
			}

			LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
			String researchKey = createIdempotencyKey(
					userId + ":research:" + source.getId() + ":" + intent, source.getQuestion(), createdAt);

			try {
				return answerAttemptsHistoryRepository.saveAndFlush(
						AnswerAttemptsHistory.createResearchAttempt(
								source, intent, researchKey, createdAt, llmModel, embeddingModel));
			} catch (DataIntegrityViolationException exception) {
				if (isResearchDuplicate(exception)) {
					throw new ChatException(ChatErrorCode.ALREADY_RESEARCHED);
				}
				throw exception;
			}
		});
	}

	private boolean isResearchDuplicate(DataIntegrityViolationException exception) {
		String message = exception.getMostSpecificCause().getMessage();
		return message != null && message.contains("uq_answer_attempts_history_source_intent");
	}

	private AnswerAttemptsHistory createRetryAttempt(Long userId, Long conversationId, String idempotencyKey) {
		boolean guest = userId == null;
		return transactionTemplate.execute(transactionStatus -> {
			// 최초 행을 잠가서 같은 질문에 대한 동시 재시도를 직렬로 처리합니다.
			(guest
					? answerAttemptsHistoryRepository
							.findGuestInitialAttemptsForLock(conversationId, idempotencyKey, 1)
					: answerAttemptsHistoryRepository
							.findInitialAttemptsForLock(userId, idempotencyKey, 1))
					.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));
			AnswerAttemptsHistory latestAttempt = (guest
					? answerAttemptsHistoryRepository
							.findFirstByUserIdIsNullAndConversationIdAndIdempotencyKeyOrderByAttemptCountDesc(
									conversationId, idempotencyKey)
					: answerAttemptsHistoryRepository
							.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(userId, idempotencyKey))
					.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));

			validateRetryAttempt(latestAttempt).ifPresent(errorCode -> {
				throw new ChatException(errorCode);
			});
			return answerAttemptsHistoryRepository.saveAndFlush(new AnswerAttemptsHistory(
					userId, latestAttempt.getConversationId(), latestAttempt.getQuestion(),
					latestAttempt.getAttemptCount() + 1, idempotencyKey,
					LocalDateTime.now().truncatedTo(ChronoUnit.MICROS), llmModel, embeddingModel));
		});
	}

	private void validateGuestQuestionLimit(Long conversationId, String idempotencyKey) {
		Conversation conversation = conversationRepository
				.findConversationForLock(conversationId)
				.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));
		int maxQuestionCount = guestChatSettingsRepository
				.findById(GuestChatSettings.SETTINGS_ID).orElseThrow().getMaxQuestionCount();
		long usedQuestionCount = answerAttemptsHistoryRepository.countGuestQuestions(
				conversationId, idempotencyKey, ChatErrorCode.NO_FAQ, ChatErrorCode.INSUFFICIENT_FAQ);
		if (usedQuestionCount >= maxQuestionCount) {
			throw new ChatException(ChatErrorCode.GUEST_QUESTION_LIMIT_REACHED);
		}
		conversation.touch();
	}

	public AnswerAttemptsHistory saveAnswerSuccess(
			AnswerAttemptsHistory attempt,
			String answer,
			List<FaqSearchResponseDto> sources,
			String userIp) {
		AnswerAttemptsHistory savedAttempt = transactionTemplate.execute(transactionStatus -> {
			AnswerAttemptsHistory currentAttempt = answerAttemptsHistoryRepository
					.findAttemptForLock(attempt.getId()).orElseThrow();
			if (!"PENDING".equals(currentAttempt.getStatus())) {
				return currentAttempt;
			}

			QuestionLog questionLog = questionLogService.saveAndFlush(
					currentAttempt.getUserId(),
					currentAttempt.getConversationId(),
					currentAttempt.getQuestion(),
					answer,
					userIp);

			List<FaqLog> faqLogs = new ArrayList<>();
			LocalDateTime now = LocalDateTime.now();
			for (int index = 0; index < sources.size(); index++) {
				FaqSearchResponseDto source = sources.get(index);
				faqLogs.add(FaqLog.builder()
						.questionLogId(questionLog.getId())
						.faq(faqRepository.getReferenceById(source.faqId()))
						.rank(index + 1)
						.similarity(source.similarityScore())
						.createdAt(now)
						.build());
			}
			faqLogRepository.saveAll(faqLogs);
			currentAttempt.succeed();
			return currentAttempt;
		});
		log.info("답변 성공 이력을 저장했습니다: 시도ID={}, 상태={}, 참고FAQ수={}",
				attempt.getId(), savedAttempt.getStatus(), sources.size());
		return savedAttempt;
	}

	public AnswerAttemptsHistory saveAnswerFailure(AnswerAttemptsHistory attempt, ErrorCode errorCode) {
		AnswerAttemptsHistory savedAttempt = transactionTemplate.execute(transactionStatus -> {
			AnswerAttemptsHistory currentAttempt = answerAttemptsHistoryRepository
					.findAttemptForLock(attempt.getId()).orElseThrow();
			if (!"PENDING".equals(currentAttempt.getStatus())) {
				return currentAttempt;
			}
			currentAttempt.fail(errorCode);
			return currentAttempt;
		});
		log.info("답변 실패 이력을 저장했습니다: 시도ID={}, 상태={}, 오류코드={}, 오류메시지={}",
				attempt.getId(), savedAttempt.getStatus(), errorCode.getCode(), errorCode.getMessage());
		return savedAttempt;
	}

	public AnswerAttemptsHistory saveAnswerTimeout(AnswerAttemptsHistory attempt) {
		// 트랜잭션 커밋이 끝난 기록으로 재시도 응답을 구성합니다.
		AnswerAttemptsHistory savedAttempt = transactionTemplate.execute(transactionStatus -> {
			answerAttemptsHistoryRepository.updatePendingAttemptToFail(
					attempt.getId(), ChatErrorCode.RESPONSE_TIMEOUT, ChatErrorCode.RESPONSE_TIMEOUT.getMessage());
			return answerAttemptsHistoryRepository.findById(attempt.getId()).orElseThrow();
		});
		log.warn("답변 생성 시간을 초과했습니다: 시도ID={}, 상태={}", attempt.getId(), savedAttempt.getStatus());
		return savedAttempt;
	}

	/** 재시도가 불가능한 사유를 반환하고, 가능하면 빈 결과를 반환합니다. */
	public Optional<ChatErrorCode> validateRetryAttempt(AnswerAttemptsHistory attempt) {
		if (attempt.getSourceAttemptId() != null) {
			return Optional.of(ChatErrorCode.RETRY_NOT_ALLOWED);
		}
		if ("PENDING".equals(attempt.getStatus())) {
			return Optional.of(ChatErrorCode.PROCESSING);
		}
		if ("SUCCESS".equals(attempt.getStatus())) {
			return Optional.of(ChatErrorCode.ALREADY_SUCCEEDED);
		}
		if (attempt.getAttemptCount() >= maxAttempts) {
			return Optional.of(ChatErrorCode.LIMIT_REACHED);
		}
		if (!"FAIL".equals(attempt.getStatus())) {
			return Optional.of(ChatErrorCode.RETRY_NOT_ALLOWED);
		}
		return Optional.empty();
	}

	static String createIdempotencyKey(Long userId, String question, LocalDateTime createdAt) {
		return createIdempotencyKey(String.valueOf(userId), question, createdAt);
	}

	static String createGuestIdempotencyKey(Long conversationId, String question, LocalDateTime createdAt) {
		return createIdempotencyKey("guest:" + conversationId, question, createdAt);
	}

	private static String createIdempotencyKey(String owner, String question, LocalDateTime createdAt) {
		String source = owner + ":" + question.length() + ":" + question + ":" + createdAt;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(source.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}
}
