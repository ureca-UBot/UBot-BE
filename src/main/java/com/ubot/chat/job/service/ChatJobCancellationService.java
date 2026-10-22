package com.ubot.chat.job.service;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.dto.response.ChatJobCancellationResponseDto;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.stream.ChatJobCancellationPublisher;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatAttemptsService;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 소유한 질문의 최신 시도만 종료하고, 커밋 뒤 그 작업 하나에만 중단을 전달합니다. */
@Service
public class ChatJobCancellationService {

	private final ChatJobRepository jobs;
	private final AnswerAttemptsHistoryRepository attempts;
	private final ChatAttemptsService chatAttemptsService;
	private final ChatJobCancellationPublisher cancellations;
	private final ChatJobQueryService jobQueries;
	private final EntityManager entityManager;
	private final TransactionTemplate transaction;

	public ChatJobCancellationService(ChatJobRepository jobs, AnswerAttemptsHistoryRepository attempts,
			ChatAttemptsService chatAttemptsService, ChatJobCancellationPublisher cancellations,
			ChatJobQueryService jobQueries, PlatformTransactionManager manager,
			EntityManager entityManager) {
		this.jobs = jobs;
		this.attempts = attempts;
		this.chatAttemptsService = chatAttemptsService;
		this.cancellations = cancellations;
		this.jobQueries = jobQueries;
		this.entityManager = entityManager;
		this.transaction = new TransactionTemplate(manager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public ChatJobCancellationResponseDto cancel(Long jobId, Long userId, Optional<Long> guestConversationId) {
		// 재시도가 먼저 새 작업을 접수했다면 이전 작업의 잠금을 놓고 최신 작업으로 다시 확인합니다.
		// 완료와 같은 작업→시도 잠금 순서를 지켜 최초 시도 취소와 완료가 교착하지 않게 합니다.
		while (true) {
			Optional<ChatJobCancellationResponseDto> result = transaction.execute(status ->
					cancelLatest(jobId, userId, guestConversationId));
			if (result.isEmpty()) continue;
			ChatJobCancellationResponseDto response = result.get();
			if (response.cancelled()) cancellations.publish(response.jobId());
			return response;
		}
	}

	private Optional<ChatJobCancellationResponseDto> cancelLatest(
			Long jobId, Long userId, Optional<Long> guestConversationId) {
		ChatJob anchor = jobs.findById(jobId).orElseThrow(this::notFound);
		AnswerAttemptsHistory original = attempts.findById(anchor.getAttemptId())
				.filter(attempt -> jobQueries.isOwner(attempt, userId, guestConversationId))
				.orElseThrow(this::notFound);
		AnswerAttemptsHistory latest = latest(original);
		ChatJob job = jobs.findByAttemptId(latest.getId()).flatMap(found -> jobs.findJobForLock(found.getId()))
				.orElseThrow(this::notFound);
		entityManager.refresh(job);
		// 기존 재시도 생성과 같은 최초 행 잠금으로 취소 뒤 새 재시도가 만들어지지 않게 합니다.
		(original.getUserId() == null
				? attempts.findGuestInitialAttemptsForLock(original.getConversationId(), original.getIdempotencyKey(), 1)
				: attempts.findInitialAttemptsForLock(original.getUserId(), original.getIdempotencyKey(), 1))
				.orElseThrow(this::notFound);
		if (!Objects.equals(latest.getId(), latest(original).getId())) return Optional.empty();
		AnswerAttemptsHistory attempt = attempts.findAttemptForLock(latest.getId()).orElseThrow(this::notFound);
		// 잠금 전에 읽어 둔 JPA 객체가 완료 이전 상태를 보관하고 있을 수 있습니다.
		entityManager.refresh(attempt);
		if (!jobQueries.isOwner(attempt, userId, guestConversationId)) throw notFound();
		if (attempt.getErrorCode() == ChatErrorCode.USER_CANCELLED) return result(job, true);
		if ("SUCCESS".equals(attempt.getStatus())) return result(job, false);
		if (job.getStatus().isFinished() && chatAttemptsService.validateRetryAttempt(attempt).isPresent()) {
			// 출력 가드 거절 등 이미 확정된 재시도 불가 사유는 취소 오류로 덮지 않습니다.
			return result(job, false);
		}
		attempt.fail(ChatErrorCode.USER_CANCELLED);
		job.fail();
		// 사용자 중단은 FAQ 부족이 아니므로 미응답·Outbox 후보를 생성하지 않습니다.
		return result(job, true);
	}

	private AnswerAttemptsHistory latest(AnswerAttemptsHistory original) {
		return (original.getUserId() == null
				? attempts.findFirstByUserIdIsNullAndConversationIdAndIdempotencyKeyOrderByAttemptCountDesc(
						original.getConversationId(), original.getIdempotencyKey())
				: attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(
						original.getUserId(), original.getIdempotencyKey())).orElseThrow(this::notFound);
	}

	private Optional<ChatJobCancellationResponseDto> result(ChatJob job, boolean cancelled) {
		return Optional.of(new ChatJobCancellationResponseDto(job.getId(), cancelled));
	}

	private ChatException notFound() {
		return new ChatException(ChatErrorCode.JOB_NOT_FOUND);
	}
}
