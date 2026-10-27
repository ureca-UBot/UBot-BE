package com.ubot.chat.job.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.repository.ChatJobQueueRepository;
import com.ubot.chat.job.repository.ChatJobQueueRepository.Claim;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.service.ChatJobCompletionService;
import com.ubot.chat.job.service.ChatJobCompletionService.Completion;
import com.ubot.chat.job.stream.ChatJobMessage;
import com.ubot.chat.job.stream.ChatJobResultStore;
import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.job.stream.ReceivedChatJob;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatJobOutcome;
import com.ubot.chat.service.ChatJobProcessor;
import com.ubot.llm.client.OpenAiCompatibleLlmClient;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.ranking.enums.RegionSido;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ubot.chat.job.support.ChatJobTestDriver;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class ChatJobRunnerLoggingTest {

	private final ChatJobStream stream = mock(ChatJobStream.class);
	private final ChatJobResultStore results = mock(ChatJobResultStore.class);
	private final ChatJobQueueRepository queue = mock(ChatJobQueueRepository.class);
	private final ChatJobRepository jobs = mock(ChatJobRepository.class);
	private final ChatJobCompletionService completion = mock(ChatJobCompletionService.class);
	private final ChatJobProcessor processor = mock(ChatJobProcessor.class);
	private final AnswerAttemptsHistoryRepository attempts = mock(AnswerAttemptsHistoryRepository.class);
	private final ChatJobOutcome outcome = new ChatJobOutcome("비공개 답변", List.of(), null, null, List.of());
	private ChatJobRunner runner;
	private ChatJobTestDriver driver;

	@BeforeEach
	void setUp() {
		var context = new ChatRequestContext(null, null, RegionSido.SEOUL);
		ChatJob job = ChatJob.queue(22L, context);
		ReflectionTestUtils.setField(job, "id", 11L);
		when(jobs.findById(11L)).thenReturn(Optional.of(job));
		LocalDateTime startedAt = LocalDateTime.now();
		when(queue.claim(anyLong(), any())).thenReturn(Optional.of(new Claim(startedAt, startedAt.plusSeconds(10))));
		when(stream.read("logging-test", 1, null)).thenReturn(
				List.of(new ReceivedChatJob("message-11", new ChatJobMessage(11L, context))), List.of());
		when(attempts.findById(22L)).thenReturn(Optional.of(AnswerAttemptsHistory.builder()
				.id(22L).userId(1L).question("비공개 질문").attemptCount(1).status("PENDING").build()));
		when(processor.process(any(), any(), any(), any())).thenReturn(outcome);
		when(completion.complete(anyLong(), any(), any(), any())).thenReturn(new Completion(true, 33L));
		createRunner();
	}

	private void createRunner() {
		var properties = new ChatWorkerProperties(1, false, Duration.ofMillis(10), "logging-test",
				Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofMinutes(5), 5,
				Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofSeconds(10),
				Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ofMillis(1));
		runner = new ChatJobRunner(stream, results, queue, jobs, completion, processor, attempts, properties);
		var recovery = new ChatJobRecoveryService(stream, queue, jobs, completion, properties, runner);
		driver = new ChatJobTestDriver(stream, runner, recovery, properties);
	}

	@AfterEach
	void closeRunner() {
		runner.close();
	}



	@Test
	void retryAndCompletionCanBeCorrelatedAcrossComputationThreadWithoutBodies(CapturedOutput output) {
		var builder = RestClient.builder().baseUrl("http://model.test");
		var server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("http://model.test/chat/completions"))
				.andRespond(withException(new IOException("비공개 질문 Bearer secret-token")));
		server.expect(requestTo("http://model.test/chat/completions"))
				.andRespond(withSuccess("""
						{"choices":[{"message":{"role":"assistant","content":"비공개 답변"},"finish_reason":"stop"}]}
						""", MediaType.APPLICATION_JSON));
		var client = new OpenAiCompatibleLlmClient(builder.build(), "ubot-chat");
		when(processor.process(any(), any(), any(), any())).thenAnswer(call -> {
			assertThat(MDC.get("jobId")).isEqualTo("11");
			assertThat(MDC.get("attemptId")).isEqualTo("22");
			client.generateAnswer(new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "비공개 질문"))));
			return outcome;
		});

		driver.processAvailableJobs();

		assertThat(output.getAll()).contains("작업ID=11", "시도ID=22", "메시지ID=message-11", "마감시각=",
				"원인예외=ResourceAccessException", "재시도횟수=1", "모델 연결 복구 후 호출이 성공",
				"결과 저장을 마쳤습니다", "답변저장=true", "실행시간=")
				.doesNotContain("비공개 질문", "비공개 답변", "secret-token");
		verify(stream).ack("message-11");
		server.verify();
		assertThat(MDC.get("jobId")).isNull();
		assertThat(MDC.get("attemptId")).isNull();
	}

	@Test
	void failedCommitLeavesMessageWithoutFalseCompletionOrSensitiveException(CapturedOutput output) {
		when(completion.complete(anyLong(), any(), any(), any())).thenThrow(
				new DataAccessResourceFailureException("INSERT INTO answers 비공개 질문", new IllegalStateException("secret-token")));

		driver.processAvailableJobs();

		assertThat(output.getAll()).contains("ACK하지 않았습니다", "작업ID=11", "시도ID=22",
				"DataAccessResourceFailureException/IllegalStateException")
				.doesNotContain("결과 저장을 마쳤습니다", "INSERT INTO answers", "비공개 질문", "secret-token");
		verify(stream, never()).ack(any());
	}

	@Test
	void staleOwnerLogsDiscardWithoutClaimingCompletion(CapturedOutput output) {
		when(completion.complete(anyLong(), any(), any(), any())).thenReturn(new Completion(false, null));

		driver.processAvailableJobs();

		assertThat(output.getAll()).contains("실행 권한을 잃어 답변 결과를 기록하지 않았습니다", "작업ID=11", "시도ID=22")
				.doesNotContain("결과 저장을 마쳤습니다");
		verify(stream, never()).ack(any());
	}

	@Test
	void expiredDeadlineLogsTimeoutAndDoesNotCallProcessor(CapturedOutput output) {
		LocalDateTime startedAt = LocalDateTime.now().minusMinutes(1);
		when(queue.claim(anyLong(), any())).thenReturn(Optional.of(new Claim(startedAt, startedAt.plusSeconds(10))));
		when(completion.complete(anyLong(), any(), any(), any())).thenReturn(new Completion(true, null));

		driver.processAvailableJobs();

		assertThat(output.getAll()).contains("실행 한도를 넘겼습니다", "작업ID=11", "시도ID=22",
				"오류코드=" + ChatErrorCode.RESPONSE_TIMEOUT.getCode(), "답변저장=false");
		verify(processor, never()).process(any(), any(), any(), any());
		verify(stream).ack("message-11");
	}

	@Test
	void redisCacheFailureKeepsCommittedAnswerAndAcknowledgesWithoutBody(CapturedOutput output) {
		doThrow(new DataAccessResourceFailureException("비공개 답변 secret-token"))
				.when(results).save(anyLong(), any(), any());

		driver.processAvailableJobs();

		assertThat(output.getAll()).contains("결과 저장을 마쳤습니다", "답변저장=true", "Redis에 저장하지 못했습니다",
				"DataAccessResourceFailureException").doesNotContain("비공개 답변", "secret-token");
		verify(stream).ack("message-11");
	}
}
