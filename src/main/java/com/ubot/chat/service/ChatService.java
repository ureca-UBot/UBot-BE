package com.ubot.chat.service;

import com.ubot.ai.service.AiService;
import com.ubot.chat.dto.response.ChatResponseDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.exception.PromptException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 기존 FAQ·AI 서비스를 호출하고 SSE로 답변 상태를 전달합니다. */
@Slf4j
@Service
public class ChatService {
	private static final int TOP_K = 3;
	private static final double CONFIDENCE_THRESHOLD = 0.75;
	private static final long STREAM_TIMEOUT_MILLIS = 180_000L;

	private final FaqVectorService faqVectorService;
	private final AiService aiService;
	private final ChatHistoryService chatHistoryService;
	private final Executor chatExecutor;

	public ChatService(
			FaqVectorService faqVectorService,
			AiService aiService,
			ChatHistoryService chatHistoryService,
			@Qualifier("chatExecutor") Executor chatExecutor
	) {
		this.faqVectorService = faqVectorService;
		this.aiService = aiService;
		this.chatHistoryService = chatHistoryService;
		this.chatExecutor = chatExecutor;
	}

	public SseEmitter createChat(Long userId, String question) {
		AnswerAttemptsHistory attempt = chatHistoryService.createAnswerAttempt(userId, question);
		return startAnswerGeneration(attempt);
	}

	public SseEmitter retryChat(Long userId, String idempotencyKey) {
		AnswerAttemptsHistory attempt = chatHistoryService.createRetryAttempt(userId, idempotencyKey);
		return startAnswerGeneration(attempt);
	}

	private SseEmitter startAnswerGeneration(AnswerAttemptsHistory attempt) {
		SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
		sendEvent(emitter, "processing", ChatResponseDto.from(attempt, "답변 생성 중…", false));
		try {
			chatExecutor.execute(() -> generateAnswer(attempt, emitter));
		} catch (RejectedExecutionException exception) {
			handleAnswerFailure(attempt, emitter, ChatErrorCode.TASK_START_FAILED.getCode(),
					ChatErrorCode.TASK_START_FAILED.getMessage());
		}
		return emitter;
	}

	private void generateAnswer(AnswerAttemptsHistory attempt, SseEmitter emitter) {
		try {
			// 기존 검색 → 유사도 판정 → AiService 흐름을 재사용합니다.
			List<FaqSearchResponseDto> results;
			try {
				results = faqVectorService.getSimilarList(attempt.getQuestion(), TOP_K);
			} catch (DataAccessException exception) {
				handleAnswerFailure(attempt, emitter, ChatErrorCode.VECTOR_SEARCH_FAILED.getCode(),
						ChatErrorCode.VECTOR_SEARCH_FAILED.getMessage());
				return;
			}
			if (results == null || results.isEmpty()) {
				handleAnswerFailure(attempt, emitter, "CHAT_NO_FAQ", "검색 결과가 없습니다.");
				return;
			}
			double score = results.get(0).similarityScore();
			if (!Double.isFinite(score) || score < CONFIDENCE_THRESHOLD) {
				handleAnswerFailure(attempt, emitter, "CHAT_INSUFFICIENT_FAQ", "정확한 답변을 찾지 못했습니다.");
				return;
			}
			LlmResponseDto answer = aiService.generateAnswer(attempt.getQuestion(), results);
			if (answer == null || !StringUtils.hasText(answer.answer())) {
				throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
			}

			AnswerAttemptsHistory savedAttempt = chatHistoryService.saveAnswerSuccess(attempt, answer.answer(), results);
			// 질문·답변·FAQ 로그와 성공 상태가 DB에 반영된 뒤 완료를 보냅니다.
			completeStream(emitter, "completed", ChatResponseDto.from(savedAttempt, answer.answer(), false));
		} catch (EmbeddingException exception) {
			handleAnswerFailure(attempt, emitter, exception.getErrorCode().getCode(), exception.getErrorCode().getMessage());
		} catch (LlmException exception) {
			handleAnswerFailure(attempt, emitter, exception.getErrorCode().name(), exception.getErrorCode().getMessage());
		} catch (PromptException exception) {
			handleAnswerFailure(attempt, emitter, "CHAT_PROMPT_NOT_READY", "답변 프롬프트가 준비되지 않았습니다.");
		} catch (DataAccessException exception) {
			handleAnswerFailure(attempt, emitter, ChatErrorCode.STORAGE_UNAVAILABLE.getCode(),
					ChatErrorCode.STORAGE_UNAVAILABLE.getMessage());
		} catch (RuntimeException exception) {
			log.error("답변 생성 실패: attemptId={}", attempt.getId(), exception);
			handleAnswerFailure(attempt, emitter, "CHAT_INTERNAL_ERROR", "답변 생성 중 오류가 발생했습니다.");
		}
	}

	private void handleAnswerFailure(AnswerAttemptsHistory attempt, SseEmitter emitter, String code, String message) {
		ChatResponseDto response;
		try {
			AnswerAttemptsHistory savedAttempt = chatHistoryService.saveAnswerFailure(attempt, code, message);
			response = ChatResponseDto.from(savedAttempt, message, chatHistoryService.canRetryAnswer(savedAttempt));
		} catch (RuntimeException exception) {
			// 실패 기록 자체를 저장하지 못했으면 정상 저장으로 알리지 않습니다.
			log.error("실패 기록 저장 실패: attemptId={}", attempt.getId(), exception);
			response = new ChatResponseDto(
					ChatErrorCode.STORAGE_UNAVAILABLE.getMessage(), false, "FAIL",
					attempt.getIdempotencyKey(), attempt.getAttemptCount(), false
			);
		}
		completeStream(emitter, "failed", response);
	}

	private void completeStream(SseEmitter emitter, String event, ChatResponseDto response) {
		sendEvent(emitter, event, response);
		try {
			emitter.complete();
		} catch (IllegalStateException ignored) {
			// 이미 연결이 종료된 경우입니다.
		}
	}

	private void sendEvent(SseEmitter emitter, String event, ChatResponseDto response) {
		try {
			emitter.send(SseEmitter.event().name(event).data(response, MediaType.APPLICATION_JSON));
		} catch (IOException | IllegalStateException exception) {
			// 브라우저가 닫혀도 진행 중인 DB 기록은 마무리합니다.
			log.debug("SSE 연결 종료: {}", event);
		}
	}
}
