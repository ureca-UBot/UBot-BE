package com.ubot.chat.service;

import com.ubot.ai.service.AiService;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.exception.PromptException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 기존 FAQ·AI 서비스를 별도 스레드에서 호출하고 최종 답변 결과를 반환합니다. */
@Slf4j
@Service
public class ChatService {
	@Value("${CHAT_TOP_K:3}")
	private int topK;

	@Value("${CHAT_CONFIDENCE_THRESHOLD:0.75}")
	private double confidenceThreshold;

	private final FaqVectorService faqVectorService;
	private final AiService aiService;
	private final ChatAttemptsService chatAttemptsService;
	private final Executor chatExecutor;

	public ChatService(
			FaqVectorService faqVectorService,
			AiService aiService,
			ChatAttemptsService chatAttemptsService,
			@Qualifier("chatExecutor") Executor chatExecutor
	) {
		this.faqVectorService = faqVectorService;
		this.aiService = aiService;
		this.chatAttemptsService = chatAttemptsService;
		this.chatExecutor = chatExecutor;
	}

	public CompletableFuture<ChatResponseDto> createChat(Long userId, String question) {
		if (!StringUtils.hasText(question) || question.length() > 4000) {
			throw new ChatException(ChatErrorCode.INVALID_CHAT_REQUEST);
		}

		AnswerAttemptsHistory attempt = chatAttemptsService.createAnswerAttempt(userId, question);
		return startAnswerGeneration(attempt);
	}

	public CompletableFuture<ChatResponseDto> retryChat(Long userId, String idempotencyKey) {
		if (idempotencyKey == null || !idempotencyKey.matches("[0-9a-f]{64}")) {
			throw new ChatException(ChatErrorCode.INVALID_CHAT_RETRY_REQUEST);
		}

		AnswerAttemptsHistory attempt = chatAttemptsService.createRetryAttempt(userId, idempotencyKey);
		return startAnswerGeneration(attempt);
	}

	private CompletableFuture<ChatResponseDto> startAnswerGeneration(AnswerAttemptsHistory attempt) {
		try {
			return CompletableFuture.supplyAsync(() -> generateAnswer(attempt), chatExecutor);
		} catch (RejectedExecutionException exception) {
			return CompletableFuture.completedFuture(handleAnswerFailure(attempt, ChatErrorCode.TASK_START_FAILED));
		}
	}

	private ChatResponseDto generateAnswer(AnswerAttemptsHistory attempt) {
		try {
			// 기존 검색 → 유사도 판정 → AiService 흐름을 재사용합니다.
			List<FaqSearchResponseDto> results;
			try {
				results = faqVectorService.getSimilarList(attempt.getQuestion(), topK);
			} catch (DataAccessException exception) {
				return handleAnswerFailure(attempt, ChatErrorCode.VECTOR_SEARCH_FAILED);
			}
			if (results == null || results.isEmpty()) {
				return handleAnswerFailure(attempt, ChatErrorCode.NO_FAQ);
			}
			double score = results.get(0).similarityScore();
			if (!Double.isFinite(score) || score < confidenceThreshold) {
				return handleAnswerFailure(attempt, ChatErrorCode.INSUFFICIENT_FAQ);
			}
			LlmResponseDto answer = aiService.generateAnswer(attempt.getQuestion(), results);
			if (answer == null || !StringUtils.hasText(answer.answer())) {
				throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
			}

			AnswerAttemptsHistory savedAttempt = chatAttemptsService.saveAnswerSuccess(attempt, answer.answer(), results);
			// 질문·답변·FAQ 로그와 성공 상태가 DB에 반영된 뒤 최종 결과를 반환합니다.
			return ChatResponseDto.from(savedAttempt, answer.answer(), false);
		} catch (GlobalException exception) {
			return handleAnswerFailure(attempt, exception.getErrorCode());
		} catch (Exception exception) {
			if (exception instanceof LlmException llmException) {
				return handleAnswerFailure(attempt, new LlmErrorCodeAdapter(llmException.getErrorCode()));
			}
			if (exception instanceof PromptException) {
				return handleAnswerFailure(attempt, ChatErrorCode.PROMPT_NOT_READY);
			}
			if (exception instanceof DataAccessException) {
				return handleAnswerFailure(attempt, ChatErrorCode.STORAGE_UNAVAILABLE);
			}
			log.error("답변 생성 실패: attemptId={}", attempt.getId(), exception);
			return handleAnswerFailure(attempt, ChatErrorCode.INTERNAL_ERROR);
		}
	}

	private ChatResponseDto handleAnswerFailure(AnswerAttemptsHistory attempt, ErrorCode errorCode) {
		ChatResponseDto response;
		try {
			AnswerAttemptsHistory savedAttempt = chatAttemptsService.saveAnswerFailure(attempt, errorCode);
			response = ChatResponseDto.from(savedAttempt, errorCode.getMessage(), chatAttemptsService.canRetryAnswer(savedAttempt));
		} catch (RuntimeException exception) {
			// 실패 기록 자체를 저장하지 못했으면 정상 저장으로 알리지 않습니다.
			log.error("실패 기록 저장 실패: attemptId={}", attempt.getId(), exception);
			response = new ChatResponseDto(
					ChatErrorCode.STORAGE_UNAVAILABLE.getMessage(), "FAIL",
					attempt.getIdempotencyKey(), attempt.getAttemptCount(), false
			);
		}
		return response;
	}

	// 기존 LLM 오류의 코드와 메시지를 유지하면서 실패 기록에 사용할 공통 규격으로 연결합니다.
	private record LlmErrorCodeAdapter(LlmErrorCode source) implements ErrorCode {
		@Override
		public HttpStatus getStatus() {
			return HttpStatus.INTERNAL_SERVER_ERROR;
		}

		@Override
		public String getCode() {
			return source.name();
		}

		@Override
		public String getMessage() {
			return source.getMessage();
		}
	}
}
