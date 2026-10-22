package com.ubot.chat.job.service;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.dto.ChatJobResult;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatAttemptsService;
import com.ubot.chat.service.ChatJobOutcome;
import com.ubot.common.ErrorCode;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.unanswered.dto.UnansweredCandidate;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// 작업·시도 상태와 질문 로그·근거 FAQ 로그·미응답 대기열 등록을 한 트랜잭션으로 커밋함. 모델 호출은 포함 안 함
@Slf4j
@Service
public class ChatJobCompletionService {
	private final ChatJobRepository chatJobRepository;
	private final AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository;
	private final ChatAttemptsService chatAttemptsService;
	private final UnansweredQuestionService unansweredQuestionService;
	private final TransactionTemplate transactionTemplate;

	public ChatJobCompletionService(
			ChatJobRepository chatJobRepository,
			AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository,
			ChatAttemptsService chatAttemptsService,
			UnansweredQuestionService unansweredQuestionService,
			PlatformTransactionManager transactionManager) {
		this.chatJobRepository = chatJobRepository;
		this.answerAttemptsHistoryRepository = answerAttemptsHistoryRepository;
		this.chatAttemptsService = chatAttemptsService;
		this.unansweredQuestionService = unansweredQuestionService;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	// 작업이 이미 그 실행의 것이 아니면 기록 안 함
	public Completion complete(long jobId, LocalDateTime startedAt, ChatJobOutcome outcome, RegionSido region) {
		return transactionTemplate.execute(status -> {
			Optional<ChatJob> locked = chatJobRepository.findJobForLock(jobId);
			if (locked.isEmpty() || !locked.get().isRunningSince(startedAt)) {
				log.warn("작업을 가진 실행이 아니어서 결과를 기록하지 않았습니다: 작업ID={}, 현재상태={}",
						jobId, locked.map(ChatJob::getStatus).orElse(null));
				return Completion.NOT_RECORDED;
			}
			ChatJob job = locked.get();
			AnswerAttemptsHistory attempt = lockAttempt(job);
			return outcome.isFailed()
					? finishFailed(job, attempt, outcome.errorCode(), outcome.unanswered())
					: finishSucceeded(job, attempt, outcome, region);
		});
	}

	// 그새 Worker가 가져갔으면 건너뜀
	public boolean failQueued(long jobId, ErrorCode errorCode) {
		return Boolean.TRUE.equals(transactionTemplate.execute(status -> chatJobRepository
				.findQueuedJobForLock(jobId)
				.map(job -> {
					finishFailed(job, lockAttempt(job), errorCode, List.of());
					return true;
				})
				.orElse(false)));
	}

	// 그새 끝났거나 다른 실행이 넘겨받았으면 건너뜀
	public boolean failStuckRunning(long jobId, LocalDateTime startedBefore, ErrorCode errorCode) {
		return Boolean.TRUE.equals(transactionTemplate.execute(status -> chatJobRepository
				.findRunningJobStartedBeforeForLock(jobId, startedBefore)
				.map(job -> {
					finishFailed(job, lockAttempt(job), errorCode, List.of());
					return true;
				})
				.orElse(false)));
	}

	private AnswerAttemptsHistory lockAttempt(ChatJob job) {
		return answerAttemptsHistoryRepository.findAttemptForLock(job.getAttemptId()).orElseThrow();
	}

	private Completion finishSucceeded(ChatJob job, AnswerAttemptsHistory attempt, ChatJobOutcome outcome, RegionSido region) {
		Optional<Long> questionLogId = chatAttemptsService.recordSuccess(attempt, outcome.answer(), outcome.subQuestions(), region);
		if (questionLogId.isEmpty()) {
			return finishWithoutRecording(job, attempt);
		}
		enqueueUnanswered(attempt, outcome.unanswered());
		job.succeed(new ChatJobResult(questionLogId.get(), outcome.store(), outcome.userData()));
		log.info("답변 작업을 완료했습니다: 작업ID={}, 시도ID={}", job.getId(), attempt.getId());
		return new Completion(true, questionLogId.get());
	}

	private Completion finishFailed(ChatJob job, AnswerAttemptsHistory attempt, ErrorCode errorCode,
			List<UnansweredCandidate> unanswered) {
		if (!chatAttemptsService.recordFailure(attempt, errorCode)) {
			return finishWithoutRecording(job, attempt);
		}
		enqueueUnanswered(attempt, unanswered);
		job.fail();
		log.info("답변 작업이 실패로 끝났습니다: 작업ID={}, 시도ID={}, 오류코드={}",
				job.getId(), attempt.getId(), errorCode.getCode());
		return new Completion(true, null);
	}

	private void enqueueUnanswered(AnswerAttemptsHistory attempt, List<UnansweredCandidate> unanswered) {
		unanswered.forEach(candidate -> unansweredQuestionService.enqueueUnansweredQuestion(attempt.getId(), candidate));
	}

	// 시도당 작업은 하나라 일어나면 안 되는 경우. 시도는 두고 작업만 닫음
	private Completion finishWithoutRecording(ChatJob job, AnswerAttemptsHistory attempt) {
		log.error("답변 시도가 이미 끝나 있어 작업만 실패로 닫습니다: 작업ID={}, 시도ID={}, 시도상태={}",
				job.getId(), attempt.getId(), attempt.getStatus());
		job.fail();
		return new Completion(true, null);
	}

	// recorded=false면 이 실행이 작업을 잃어 기록 안 함
	public record Completion(boolean recorded, Long questionLogId) {
		static final Completion NOT_RECORDED = new Completion(false, null);
	}
}
