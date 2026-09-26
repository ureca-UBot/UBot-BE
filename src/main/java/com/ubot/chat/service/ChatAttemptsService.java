package com.ubot.chat.service;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.common.ErrorCode;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.entity.FaqLog;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.llm.enums.LlmErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 시도 횟수·멱등키와 JPA 기록 저장을 담당합니다. */
@Service
public class ChatAttemptsService {
	@Value("${CHAT_MAX_ATTEMPTS:3}")
	private int maxAttempts;

	private final AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository;
	private final QuestionLogRepository questionLogRepository;
	private final FaqLogRepository faqLogRepository;
	private final FaqRepository faqRepository;
	private final TransactionTemplate transactionTemplate;

	@Value("${spring.ai.ollama.chat.options.model:${OLLAMA_CHAT_MODEL:}}")
	private String llmModel;

	@Value("${ollama.embedding.model:}")
	private String embeddingModel;

	public ChatAttemptsService(
			AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository,
			QuestionLogRepository questionLogRepository,
			FaqLogRepository faqLogRepository,
			FaqRepository faqRepository,
			PlatformTransactionManager transactionManager
	) {
		this.answerAttemptsHistoryRepository = answerAttemptsHistoryRepository;
		this.questionLogRepository = questionLogRepository;
		this.faqLogRepository = faqLogRepository;
		this.faqRepository = faqRepository;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		// 기록 저장 트랜잭션만 열고, 모델 호출 중에는 DB 연결이나 행 잠금을 유지하지 않습니다.
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public AnswerAttemptsHistory createAnswerAttempt(Long userId, String question) {
		String input = question.strip();
		LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
		String idempotencyKey = createIdempotencyKey(userId, input, createdAt);
		return transactionTemplate.execute(transactionStatus ->
				answerAttemptsHistoryRepository.saveAndFlush(new AnswerAttemptsHistory(
						userId, input, 1, idempotencyKey, createdAt, llmModel, embeddingModel
				))
		);
	}

	public AnswerAttemptsHistory createRetryAttempt(Long userId, String idempotencyKey) {
		return transactionTemplate.execute(transactionStatus -> {
			// 최초 행을 잠가서 같은 질문에 대한 동시 재시도를 직렬로 처리합니다.
			answerAttemptsHistoryRepository
					.findInitialAttemptsForLock(idempotencyKey, 1)
					.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));
			AnswerAttemptsHistory latestAttempt = answerAttemptsHistoryRepository
					.findFirstByIdempotencyKeyOrderByAttemptCountDesc(idempotencyKey)
					.orElseThrow(() -> new ChatException(ChatErrorCode.ATTEMPT_NOT_FOUND));

			validateRetryAttempt(latestAttempt);
			return answerAttemptsHistoryRepository.saveAndFlush(new AnswerAttemptsHistory(
					userId, latestAttempt.getQuestion(), latestAttempt.getAttemptCount() + 1,
					idempotencyKey, LocalDateTime.now().truncatedTo(ChronoUnit.MICROS), llmModel, embeddingModel
			));
		});
	}

	public AnswerAttemptsHistory saveAnswerSuccess(
			AnswerAttemptsHistory attempt,
			String answer,
			List<FaqSearchResponseDto> sources
	) {
		return transactionTemplate.execute(transactionStatus -> {
			AnswerAttemptsHistory currentAttempt = answerAttemptsHistoryRepository
					.findById(attempt.getId()).orElseThrow();
			QuestionLog questionLog = questionLogRepository.saveAndFlush(new QuestionLog(
					attempt.getUserId(), attempt.getQuestion(), answer, attempt.getCreatedAt()
			));
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
	}

	public AnswerAttemptsHistory saveAnswerFailure(AnswerAttemptsHistory attempt, ErrorCode errorCode) {
		return transactionTemplate.execute(transactionStatus -> {
			AnswerAttemptsHistory currentAttempt = answerAttemptsHistoryRepository
					.findById(attempt.getId()).orElseThrow();
			currentAttempt.fail(errorCode);
			return currentAttempt;
		});
	}

	public boolean canRetryAnswer(AnswerAttemptsHistory attempt) {
		if (!"FAIL".equals(attempt.getStatus()) || attempt.getAttemptCount() >= maxAttempts) {
			return false;
		}
		String errorCode = attempt.getErrorCode();
		return ChatErrorCode.VECTOR_SEARCH_FAILED.getCode().equals(errorCode)
				|| Arrays.stream(EmbeddingErrorCode.values()).anyMatch(code -> code.getCode().equals(errorCode))
				|| Arrays.stream(LlmErrorCode.values()).anyMatch(code -> code.name().equals(errorCode));
	}

	private void validateRetryAttempt(AnswerAttemptsHistory attempt) {
		if ("PENDING".equals(attempt.getStatus())) {
			throw new ChatException(ChatErrorCode.PROCESSING);
		}
		if ("SUCCESS".equals(attempt.getStatus())) {
			throw new ChatException(ChatErrorCode.ALREADY_SUCCEEDED);
		}
		if (attempt.getAttemptCount() >= maxAttempts) {
			throw new ChatException(ChatErrorCode.LIMIT_REACHED);
		}
		if (!canRetryAnswer(attempt)) {
			throw new ChatException(ChatErrorCode.RETRY_NOT_ALLOWED);
		}
	}

	static String createIdempotencyKey(Long userId, String question, LocalDateTime createdAt) {
		String source = userId + ":" + question.length() + ":" + question + ":" + createdAt;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(source.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}
}
