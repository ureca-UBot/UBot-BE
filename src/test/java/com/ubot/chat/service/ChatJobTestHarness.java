package com.ubot.chat.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.entity.QuestionLog;
import com.ubot.chat.job.dto.ChatJobResult;
import com.ubot.chat.job.dto.response.ChatJobStatusResponseDto;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.repository.ChatJobQueueRepository;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.service.ChatJobCompletionService;
import com.ubot.chat.job.service.ChatJobQueryService;
import com.ubot.chat.job.service.ChatSubmissionService;
import com.ubot.chat.job.service.ChatSubmissionService.ChatSubmission;
import com.ubot.chat.job.stream.ChatJobMessage;
import com.ubot.chat.job.stream.ChatJobResultStore;
import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.chat.job.worker.ChatJobWorker;
import com.ubot.chat.job.worker.ChatWorkerProperties;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.conversation.repository.ConversationRepository;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

// DB·Redis 없이 접수 → 스트림 → Worker 처리 → 상태 조회를 잇는 테스트 준비물
// 저장소·스트림·결과 저장소는 메모리로 흉내 내고 접수·완료·조회 서비스와 Worker는 실제 코드를 씀
final class ChatJobTestHarness {
	static final ChatWorkerProperties WORKER_PROPERTIES = new ChatWorkerProperties(
			1, false, Duration.ofSeconds(2), "test-worker", Duration.ofMinutes(10), Duration.ofSeconds(30),
			Duration.ofMinutes(5), 3, Duration.ofSeconds(15), Duration.ofHours(24));

	final ChatJobRepository jobRepository = mock(ChatJobRepository.class);
	final ChatJobQueueRepository queueRepository = mock(ChatJobQueueRepository.class);
	final QuestionLogRepository questionLogRepository = mock(QuestionLogRepository.class);
	final ChatJobStream stream = mock(ChatJobStream.class);
	final ChatJobResultStore resultStore = mock(ChatJobResultStore.class);
	final ConversationRepository conversationRepository = mock(ConversationRepository.class);
	final List<ChatJob> jobs = new ArrayList<>();
	// 읽힘 여부와 무관하게 발행된 메시지 전부
	final List<ChatJobMessage> published = new ArrayList<>();
	final List<String> acked = new ArrayList<>();
	final ChatSubmissionService submissionService;
	final UnansweredQuestionService unanswered = mock(UnansweredQuestionService.class);
	final ChatJobCompletionService completionService;
	final ChatJobQueryService queryService;
	final ChatJobWorker worker;
	private final Deque<ReceivedChatJob> unread = new ArrayDeque<>();
	private final AnswerAttemptsHistoryRepository attemptRepository;
	private final Map<Long, AnswerAttemptsHistory> knownAttempts = new HashMap<>();
	private final Map<Long, QuestionLog> questionLogs = new HashMap<>();
	private final Map<Long, ChatJobResult> results = new HashMap<>();
	final Set<Long> publishedMarks = new HashSet<>();
	private int failingPublishes;

	// findById·findAttemptForLock은 테스트가 정함
	ChatJobTestHarness(
			AnswerAttemptsHistoryRepository attemptRepository,
			ChatAttemptsService attemptsService,
			ChatAnswerProcessor processor,
			PlatformTransactionManager transactionManager) {
		this.attemptRepository = attemptRepository;
		stubRepositories();
		stubStream();
		submissionService = new ChatSubmissionService(jobRepository, stream, transactionManager);
		completionService = new ChatJobCompletionService(
				jobRepository, attemptRepository, attemptsService, unanswered, transactionManager);
		when(conversationRepository.existsByIdAndDeletedAtIsNull(anyLong())).thenReturn(true);
		queryService = new ChatJobQueryService(
				jobRepository, attemptRepository, questionLogRepository, attemptsService, resultStore, conversationRepository);
		worker = new ChatJobWorker(stream, resultStore, queueRepository, jobRepository, completionService,
				new ChatJobProcessor(processor), attemptRepository, WORKER_PROPERTIES);
	}

	// ChatAttemptsService까지 흉내 내는 테스트용. 시도는 knowAttempts로 알려 줌
	ChatJobTestHarness(ChatAttemptsService attemptsService, ChatAnswerProcessor processor) {
		this(mock(AnswerAttemptsHistoryRepository.class), attemptsService, processor, transactionManager());
	}

	static PlatformTransactionManager transactionManager() {
		PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
		when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
		return transactionManager;
	}

	// 흉내 낸 시도를 작업 처리에서도 찾게 함
	ChatJobTestHarness knowAttempts(AnswerAttemptsHistory... attempts) {
		for (AnswerAttemptsHistory attempt : attempts) {
			knownAttempts.put(attempt.getId(), attempt);
		}
		when(attemptRepository.findAttemptForLock(anyLong()))
				.thenAnswer(call -> Optional.ofNullable(knownAttempts.get(call.<Long>getArgument(0))));
		when(attemptRepository.findById(anyLong()))
				.thenAnswer(call -> Optional.ofNullable(knownAttempts.get(call.<Long>getArgument(0))));
		return this;
	}

	// 상태 조회가 이 로그에서 답변을 읽음
	QuestionLog saveLog(QuestionLog questionLog) {
		if (questionLog.getId() == null) {
			ReflectionTestUtils.setField(questionLog, "id", (long) questionLogs.size() + 1);
		}
		questionLogs.put(questionLog.getId(), questionLog);
		return questionLog;
	}

	// 답변을 질문 로그로 남기고 그 ID를 돌려줌
	Optional<Long> logAnswer(String answer) {
		return Optional.of(saveLog(QuestionLog.builder().answer(answer).build()).getId());
	}

	// 다음 count번 발행을 실패시킴(Redis 장애 가정)
	void failPublishes(int count) {
		failingPublishes = count;
	}

	int runQueuedJobs() {
		return worker.processAvailableJobs();
	}

	ChatJob job(ChatSubmission submission) {
		return jobs.stream().filter(job -> job.getId().equals(submission.response().jobId())).findFirst().orElseThrow();
	}

	ChatJobStatusResponseDto memberStatus(ChatSubmission submission, Long userId) {
		return queryService.getStatus(submission.response().jobId(), userId, Optional.empty());
	}

	ChatJobStatusResponseDto guestStatus(ChatSubmission submission, Long conversationId) {
		return queryService.getStatus(submission.response().jobId(), null, Optional.of(conversationId));
	}

	private void stubRepositories() {
		when(jobRepository.saveAndFlush(any())).thenAnswer(call -> {
			ChatJob job = call.getArgument(0);
			ReflectionTestUtils.setField(job, "id", (long) jobs.size() + 1);
			jobs.add(job);
			return job;
		});
		when(jobRepository.findById(anyLong())).thenAnswer(call -> findJob(call.getArgument(0)));
		when(jobRepository.findJobForLock(anyLong())).thenAnswer(call -> findJob(call.getArgument(0)));
		when(questionLogRepository.findById(anyLong())).thenAnswer(call ->
				Optional.ofNullable(questionLogs.get(call.<Long>getArgument(0))));
		// 재발행 조회는 대기 작업 전부, 대기 한도 조회는 빈 목록을 돌려줌
		when(queueRepository.findQueuedOlderThan(any(), anyInt())).thenAnswer(call ->
				WORKER_PROPERTIES.republishAfter().equals(call.getArgument(0))
						? jobs.stream().filter(job -> job.getStatus() == ChatJobStatus.QUEUED).map(ChatJob::getId).toList()
						: List.of());
		when(queueRepository.claim(anyLong(), any())).thenAnswer(call -> findJob(call.getArgument(0))
				.filter(job -> job.getStatus() == ChatJobStatus.QUEUED)
				.map(job -> {
					LocalDateTime startedAt = call.getArgument(1);
					ReflectionTestUtils.setField(job, "status", ChatJobStatus.RUNNING);
					ReflectionTestUtils.setField(job, "startedAt", startedAt);
					return startedAt;
				}));
	}

	private void stubStream() {
		when(stream.publish(any())).thenAnswer(call -> {
			if (failingPublishes > 0) {
				failingPublishes--;
				throw new RedisConnectionFailureException("redis down");
			}
			ChatJobMessage message = call.getArgument(0);
			published.add(message);
			publishedMarks.add(message.jobId());
			String messageId = "message-" + published.size();
			unread.add(new ReceivedChatJob(messageId, message));
			return messageId;
		});
		when(stream.read(anyString(), anyInt(), any())).thenAnswer(call ->
				unread.isEmpty() ? List.of() : List.of(unread.poll()));
		doAnswer(call -> acked.add(call.getArgument(0))).when(stream).ack(anyString());
		when(stream.findUnpublished(any())).thenAnswer(call -> call.<List<Long>>getArgument(0).stream()
				.filter(jobId -> !publishedMarks.contains(jobId)).toList());
		when(stream.publishIfAbsent(any())).thenAnswer(call -> {
			ChatJobMessage message = call.getArgument(0);
			if (publishedMarks.contains(message.jobId())) return false;
			stream.publish(message);
			return true;
		});
		doAnswer(call -> publishedMarks.remove(call.<Long>getArgument(0))).when(stream).clearPublished(anyLong());
		doAnswer(call -> results.put(call.getArgument(0), call.getArgument(1)))
				.when(resultStore).save(anyLong(), any(), any());
		when(resultStore.find(anyLong())).thenAnswer(call -> Optional.ofNullable(results.get(call.<Long>getArgument(0))));
	}

	private Optional<ChatJob> findJob(Long jobId) {
		return jobs.stream().filter(job -> job.getId().equals(jobId)).findFirst();
	}
}
