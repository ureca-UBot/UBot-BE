package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.Location;
import com.ubot.chat.dto.response.ChatStoreDto;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.entity.converter.AnswerAttemptErrorCodeConverter;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;
import com.ubot.unanswered.dto.UnansweredCandidate;
import java.util.List;
import java.util.concurrent.CancellationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

// 예외를 시도에 기록할 오류 코드로 바꿈. 저장은 안 하고 모델 호출 중 트랜잭션을 열지 않음
@Slf4j
@Component
public class ChatJobProcessor {
	private final ChatAnswerProcessor chatAnswerProcessor;

	public ChatJobProcessor(ChatAnswerProcessor chatAnswerProcessor) {
		this.chatAnswerProcessor = chatAnswerProcessor;
	}

	// checkCancellation은 작업을 잃거나 종료 중이면 CancellationException을 던짐. 이 예외는 그대로 전달함
	public ChatJobOutcome process(AnswerAttemptsHistory attempt, Location location, Runnable checkCancellation,
			List<UnansweredCandidate> unanswered) {
		long startedAt = System.nanoTime();
		log.info("답변 생성 작업을 시작했습니다: 시도ID={}, 재시도횟수={}", attempt.getId(), attempt.getAttemptCount());
		try {
			ChatAnswerResult result = chatAnswerProcessor.generateAnswer(attempt, location, checkCancellation, unanswered);
			if (result.isFailed()) {
				return ChatJobOutcome.failed(result.errorCode(), unanswered);
			}
			AiAnswer answer = result.answer();
			log.info("답변을 생성했습니다: 시도ID={}, 처리시간={}ms", attempt.getId(), elapsedMillis(startedAt));
			return ChatJobOutcome.answered(answer.answer(), result.subQuestions(), result.questionEmbeddings(),
					ChatStoreDto.of(answer.locationRequired(), answer.storeMap()), answer.userData(), unanswered);
		} catch (CancellationException exception) {
			throw exception;
		} catch (GlobalException exception) {
			ErrorCode errorCode = exception.getErrorCode();
			log.warn("답변 생성이 업무 예외로 종료되었습니다: 시도ID={}, 오류코드={}, 오류메시지={}",
					attempt.getId(), errorCode.getCode(), errorCode.getMessage());
			// 시도에는 CHAT·EM·LLM·PROMPT 코드만 저장됨. 다른 코드는 저장 실패로 재실행되므로 INTERNAL_ERROR로 바꿈
			if (!AnswerAttemptErrorCodeConverter.supports(errorCode)) {
				log.error("답변 시도에 저장할 수 없는 오류 코드라 INTERNAL_ERROR로 기록합니다: 시도ID={}, 오류코드={}",
						attempt.getId(), errorCode.getCode());
				return ChatJobOutcome.failed(ChatErrorCode.INTERNAL_ERROR, unanswered);
			}
			return ChatJobOutcome.failed(errorCode, unanswered);
		} catch (DataAccessException exception) {
			log.warn("답변 저장소 접근에 실패했습니다: 시도ID={}", attempt.getId(), exception);
			return ChatJobOutcome.failed(ChatErrorCode.STORAGE_UNAVAILABLE, unanswered);
		} catch (RuntimeException exception) {
			log.error("답변 생성에 실패했습니다: 시도ID={}", attempt.getId(), exception);
			return ChatJobOutcome.failed(ChatErrorCode.INTERNAL_ERROR, unanswered);
		}
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
