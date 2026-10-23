package com.ubot.chat.service;

import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.chat.util.PersonalInfoMasker;
import com.ubot.common.ErrorCode;
import com.ubot.conversation.entity.Conversation;
import com.ubot.conversation.exception.ConversationErrorCode;
import com.ubot.conversation.exception.ConversationException;
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
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.ranking.enums.RegionSido;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;

/** 시도 횟수·멱등키와 JPA 기록 저장을 담당합니다. */
@Service
@Slf4j
public class ChatAttemptsService {
	// 답변 없음(CHAT-012·013)과 입력 가드 거절(CHAT-022·023). 질문이 같으면 다시 시도해도 결과가 같음
	private static final Set<ErrorCode> SAME_RESULT_FAILURES = Set.of(
			ChatErrorCode.NO_FAQ, ChatErrorCode.INSUFFICIENT_FAQ,
			ChatErrorCode.PROMPT_INJECTION_DETECTED, ChatErrorCode.NO_USABLE_QUESTION);

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
		// 시도 생성은 작업 접수와 같은 트랜잭션에 참여함
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
	}

	public AnswerAttemptsHistory createAnswerAttempt(Long userId, String question) {
		return createAnswerAttempt(userId, null, question);
	}

	public AnswerAttemptsHistory createAnswerAttempt(Long userId, Long conversationId, String question) {
		String input = PersonalInfoMasker.mask(question.strip());
		LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
		String idempotencyKey = createIdempotencyKey(userId, input, createdAt);
		return transactionTemplate.execute(transactionStatus -> {
			if (conversationId != null) {
				lockActiveConversation(conversationId, userId);
			}
			return answerAttemptsHistoryRepository.saveAndFlush(new AnswerAttemptsHistory(
					userId, conversationId, input, 1, idempotencyKey, createdAt, llmModel, embeddingModel));
		});
	}

	public AnswerAttemptsHistory createGuestAnswerAttempt(Long conversationId, String question) {
		String input = PersonalInfoMasker.mask(question.strip());
		LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
		String idempotencyKey = createGuestIdempotencyKey(conversationId, input, createdAt);
		return transactionTemplate.execute(transactionStatus -> {
			validateGuestQuestionLimit(conversationId, idempotencyKey);
			return answerAttemptsHistoryRepository.saveAndFlush(new AnswerAttemptsHistory(
					null, conversationId, input, 1, idempotencyKey, createdAt, llmModel, embeddingModel));
		});
	}

	/** 회원 본인의 최신 시도 기록을 조회합니다. */
	public AnswerAttemptsHistory getLatestAnswerAttempt(Long userId, String idempotencyKey) {
		return answerAttemptsHistoryRepository
				.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(userId, idempotencyKey)
				.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));
	}

	/** 해당 비회원 대화의 최신 시도 기록만 조회합니다. */
	public AnswerAttemptsHistory getLatestGuestAnswerAttempt(Long conversationId, String idempotencyKey) {
		return answerAttemptsHistoryRepository
				.findFirstByUserIdIsNullAndConversationIdAndIdempotencyKeyOrderByAttemptCountDesc(
						conversationId, idempotencyKey)
				.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));
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
			if ("PENDING".equals(latest.getStatus())) {
				throw new ChatException(ChatErrorCode.RESEARCH_NOT_ALLOWED);
			}
			if (latest.getConversationId() != null) {
				lockActiveConversation(latest.getConversationId(), userId);
			}

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
			AnswerAttemptsHistory source = guest
					? getLatestGuestAnswerAttempt(conversationId, idempotencyKey)
					: getLatestAnswerAttempt(userId, idempotencyKey);
			if ("PENDING".equals(source.getStatus())) {
				validateRetryAttempt(source).ifPresent(errorCode -> {
					throw new ChatException(errorCode);
				});
			}
			if (source.getConversationId() != null) {
				// 대화 → 시도 순서로 잠가 삭제·게스트 승계와 잠금 순서를 맞춥니다.
				lockActiveConversation(source.getConversationId(), userId);
			}
			// 최초 행을 잠가서 같은 질문에 대한 동시 재시도를 직렬로 처리합니다.
			(guest
					? answerAttemptsHistoryRepository
							.findGuestInitialAttemptsForLock(conversationId, idempotencyKey, 1)
					: answerAttemptsHistoryRepository
							.findInitialAttemptsForLock(userId, idempotencyKey, 1))
					.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));
			AnswerAttemptsHistory latestAttempt = guest
					? getLatestGuestAnswerAttempt(conversationId, idempotencyKey)
					: getLatestAnswerAttempt(userId, idempotencyKey);

			validateRetryAttempt(latestAttempt).ifPresent(errorCode -> {
				throw new ChatException(errorCode);
			});
			return answerAttemptsHistoryRepository.saveAndFlush(new AnswerAttemptsHistory(
					userId, latestAttempt.getConversationId(), PersonalInfoMasker.mask(latestAttempt.getQuestion()),
					latestAttempt.getAttemptCount() + 1, idempotencyKey,
					LocalDateTime.now().truncatedTo(ChronoUnit.MICROS), llmModel, embeddingModel));
		});
	}

	private void validateGuestQuestionLimit(Long conversationId, String idempotencyKey) {
		Conversation conversation = lockActiveConversation(conversationId, null);
		int maxQuestionCount = guestChatSettingsRepository
				.findById(GuestChatSettings.SETTINGS_ID).orElseThrow().getMaxQuestionCount();
		long usedQuestionCount = answerAttemptsHistoryRepository.countGuestQuestions(
				conversationId, idempotencyKey, ChatErrorCode.NO_FAQ, ChatErrorCode.INSUFFICIENT_FAQ);
		if (usedQuestionCount >= maxQuestionCount) {
			throw new ChatException(ChatErrorCode.GUEST_QUESTION_LIMIT_REACHED);
		}
		conversation.touch();
	}

	private Conversation lockActiveConversation(Long conversationId, Long userId) {
		return conversationRepository.findConversationForLock(conversationId)
				.filter(conversation -> !conversation.isDeleted())
				.filter(conversation -> userId == null
						? conversation.isGuest() : userId.equals(conversation.getUserId()))
				.orElseThrow(() -> new ConversationException(ConversationErrorCode.CONVERSATION_NOT_FOUND));
	}

	// 작업 완료와 함께 커밋되도록 호출자 트랜잭션 안에서만 부름. 이미 끝난 시도면 기록 없이 빈 값
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Long> recordSuccess(
			AnswerAttemptsHistory lockedAttempt,
			String answer,
			List<SubQuestionFaqs> subQuestions,
			RegionSido region) {
		if (!"PENDING".equals(lockedAttempt.getStatus())) {
			return Optional.empty();
		}
		// 근거를 못 찾은 하위 질문도 행을 남깁니다. 대화 기록은 턴마다 1번 행을 읽으므로 1번이 빠지면 턴이 사라집니다.
		// 하위 질문 없이 넘어오면 원문 하나로 저장해 1번 행을 남깁니다.
		if (subQuestions.isEmpty()) {
			subQuestions = List.of(new SubQuestionFaqs(lockedAttempt.getQuestion(), List.of(), lockedAttempt.getQuestion()));
		}
		List<QuestionLog> questionLogs = questionLogService.saveTurn(
				lockedAttempt.getUserId(),
				lockedAttempt.getConversationId(),
				lockedAttempt.getId(),
				lockedAttempt.getQuestion(),
				subQuestions,
				answer,
				region);

		// 검색 결과 순위는 하위 질문마다 따로 매깁니다. 같은 FAQ가 여러 하위 질문에서 걸리면 각각 남습니다.
		List<FaqLog> faqLogs = new ArrayList<>();
		LocalDateTime now = LocalDateTime.now();
		for (int subIndex = 0; subIndex < subQuestions.size(); subIndex++) {
			List<FaqSearchResponseDto> sources = subQuestions.get(subIndex).faqs();
			for (int index = 0; index < sources.size(); index++) {
				FaqSearchResponseDto source = sources.get(index);
				faqLogs.add(FaqLog.builder()
						.questionLogId(questionLogs.get(subIndex).getId())
						.faq(faqRepository.getReferenceById(source.faqId()))
						.rank(index + 1)
						.similarity(source.similarityScore())
						.createdAt(now)
						.build());
			}
		}
		faqLogRepository.saveAll(faqLogs);
		lockedAttempt.succeed();
		log.info("답변 성공 이력을 기록했습니다: 시도ID={}, 하위질문수={}, 참고FAQ수={}", lockedAttempt.getId(),
				subQuestions.size(), subQuestions.stream().mapToInt(subQuestion -> subQuestion.faqs().size()).sum());
		// 작업 결과는 턴마다 하나라 1번 행을 가리킵니다. 1번 행에 턴의 원문과 답변이 들어 있습니다.
		return Optional.of(questionLogs.getFirst().getId());
	}

	// recordSuccess와 같이 호출자 트랜잭션 안에서만 부름
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean recordFailure(AnswerAttemptsHistory lockedAttempt, ErrorCode errorCode) {
		if (!"PENDING".equals(lockedAttempt.getStatus())) {
			return false;
		}
		lockedAttempt.fail(errorCode);
		log.info("답변 실패 이력을 기록했습니다: 시도ID={}, 오류코드={}, 오류메시지={}",
				lockedAttempt.getId(), errorCode.getCode(), errorCode.getMessage());
		return true;
	}

	/** 재시도가 불가능한 사유를 반환하고, 가능하면 빈 결과를 반환합니다. */
	public Optional<ChatErrorCode> validateRetryAttempt(AnswerAttemptsHistory attempt) {
		// 질문이 너무 많아 거절한 경우는 다시 시도해도 결과가 같으므로 재시도를 막습니다.
		if (attempt.getSourceAttemptId() != null || attempt.getErrorCode() == ChatErrorCode.TOO_MANY_SUB_QUESTIONS) {
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
		// 출력 가드가 거절한 질문은 API를 직접 재호출해도 새 시도를 만들지 않습니다.
		if (attempt.getErrorCode() == LlmErrorCode.LLM_OUTPUT_REJECTED
				|| attempt.getErrorCode() == ChatErrorCode.USER_CANCELLED) {
			return Optional.of(ChatErrorCode.RETRY_NOT_ALLOWED);
		}
		// 같은 질문이면 다시 시도해도 결과가 같은 실패. 자동 재시도가 같은 검색·검사를 반복하지 않게 막음
		if (SAME_RESULT_FAILURES.contains(attempt.getErrorCode())) {
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
