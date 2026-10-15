package com.ubot.chat.job.service;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.dto.response.ChatJobSubmissionResponseDto;
import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.stream.ChatJobMessage;
import com.ubot.chat.job.stream.ChatJobStream;
import java.util.function.Supplier;
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

	public ChatSubmissionService(
			ChatJobRepository chatJobRepository,
			ChatJobStream chatJobStream,
			PlatformTransactionManager transactionManager) {
		this.chatJobRepository = chatJobRepository;
		this.chatJobStream = chatJobStream;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	// 검증 예외면 시도·작업 모두 저장 안 되고 발행도 안 함
	public ChatSubmission submit(Supplier<AnswerAttemptsHistory> createAttempt, ChatRequestContext context) {
		ChatSubmission submission = transactionTemplate.execute(status -> {
			AnswerAttemptsHistory attempt = createAttempt.get();
			ChatJob job = chatJobRepository.saveAndFlush(ChatJob.queue(attempt.getId(), context));
			log.info("답변 작업을 접수했습니다: 작업ID={}, 시도ID={}, 재시도횟수={}",
					job.getId(), attempt.getId(), attempt.getAttemptCount());
			return ChatSubmission.of(job, attempt);
		});
		ChatJobMessage message = new ChatJobMessage(submission.response().jobId(), context);
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			// 외부 트랜잭션이면 실제 커밋 뒤 발행함
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override public void afterCommit() { publishQuietly(message); }
			});
		} else {
			publishQuietly(message);
		}
		return submission;
	}

	// 발행 못 한 작업은 Worker가 DB 요청 정보로 재발행함
	private void publishQuietly(ChatJobMessage message) {
		try {
			chatJobStream.publish(message);
		} catch (RuntimeException exception) {
			log.error("답변 작업 메시지를 발행하지 못했습니다: 작업ID={}", message.jobId(), exception);
		}
	}

	// conversationId: 응답에서 공개 ID로 바꿀 내부 대화 ID
	public record ChatSubmission(ChatJobSubmissionResponseDto response, Long conversationId) {
		static ChatSubmission of(ChatJob job, AnswerAttemptsHistory attempt) {
			return new ChatSubmission(ChatJobSubmissionResponseDto.of(job, attempt), attempt.getConversationId());
		}
	}
}
