package com.ubot.chat.job.service;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.dto.response.ChatJobSubmissionResponseDto;
import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.stream.ChatJobMessage;
import com.ubot.chat.job.stream.ChatJobStream;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.Locale;
import java.util.function.Supplier;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 시도와 작업을 한 트랜잭션으로 접수하고 커밋 뒤 발행함. 발행에 실패해도 작업은 DB에 남아 접수 성공으로 응답함
@Slf4j
@Service
public class ChatSubmissionService {
	private final ChatJobRepository chatJobRepository;
	private final ChatJobStream chatJobStream;
	private final TransactionTemplate transactionTemplate;
	private final MeterRegistry metrics;
	@Value("${app.chat.worker.max-queue-wait:10m}")
	private Duration maxQueueWait = Duration.ofMinutes(10);

	// 지표를 보지 않는 테스트용. 전역 레지스트리는 등록된 저장소가 없으면 기록하지 않음
	public ChatSubmissionService(
			ChatJobRepository chatJobRepository,
			ChatJobStream chatJobStream,
			PlatformTransactionManager transactionManager) {
		this(chatJobRepository, chatJobStream, transactionManager, Metrics.globalRegistry);
	}

	@Autowired
	public ChatSubmissionService(
			ChatJobRepository chatJobRepository,
			ChatJobStream chatJobStream,
			PlatformTransactionManager transactionManager,
			MeterRegistry metrics) {
		this.chatJobRepository = chatJobRepository;
		this.chatJobStream = chatJobStream;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.metrics = metrics;
	}

	// 검증 예외면 시도·작업 모두 저장 안 되고 발행도 안 함. 접수 수는 커밋된 것만 셈
	public ChatSubmission submit(SubmissionType type, boolean member, Supplier<AnswerAttemptsHistory> createAttempt,
			ChatRequestContext context) {
		ChatSubmission submission = transactionTemplate.execute(status -> {
			AnswerAttemptsHistory attempt = createAttempt.get();
			ChatJob job = chatJobRepository.saveAndFlush(ChatJob.queue(attempt.getId(), context, maxQueueWait));
			log.info("답변 작업을 접수했습니다: 작업ID={}, 시도ID={}, 재시도횟수={}",
					job.getId(), attempt.getId(), attempt.getAttemptCount());
			return ChatSubmission.of(job, attempt);
		});
		ChatJobMessage message = new ChatJobMessage(submission.response().jobId(), context);
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			// 외부 트랜잭션이면 실제 커밋 뒤 발행함
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override public void afterCommit() {
					countSubmitted(type, member);
					publishQuietly(message);
				}
			});
		} else {
			countSubmitted(type, member);
			publishQuietly(message);
		}
		return submission;
	}

	// 재시도 비율은 type=retry / type=question으로 계산함
	private void countSubmitted(SubmissionType type, boolean member) {
		metrics.counter("chat.jobs.submitted",
				"type", type.name().toLowerCase(Locale.ROOT),
				"user", member ? "member" : "guest").increment();
	}

	// 발행 못 한 작업은 Worker가 DB 요청 정보로 재발행함
	private void publishQuietly(ChatJobMessage message) {
		try {
			chatJobStream.publish(message);
		} catch (RuntimeException exception) {
			log.error("답변 작업 메시지를 발행하지 못했습니다: 작업ID={}", message.jobId(), exception);
		}
	}

	// 첫 질문, 같은 질문의 재시도, 다른 의도로 다시 검색
	public enum SubmissionType {
		QUESTION, RETRY, RESEARCH
	}

	// conversationId: 응답에서 공개 ID로 바꿀 내부 대화 ID
	public record ChatSubmission(ChatJobSubmissionResponseDto response, Long conversationId) {
		static ChatSubmission of(ChatJob job, AnswerAttemptsHistory attempt) {
			return new ChatSubmission(ChatJobSubmissionResponseDto.of(job, attempt), attempt.getConversationId());
		}
	}
}
