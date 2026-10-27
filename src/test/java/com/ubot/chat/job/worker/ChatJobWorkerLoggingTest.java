package com.ubot.chat.job.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.chat.job.repository.ChatJobQueueRepository;
import com.ubot.chat.job.service.ChatJobCompletionService;
import com.ubot.chat.job.service.ChatJobCompletionService.Completion;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(OutputCaptureExtension.class)
class ChatJobWorkerLoggingTest extends ChatJobTestSupport {
	@Test
	void repeatedStreamFailureWarnsOnceAndRecoveryResumesProcessing(CapturedOutput output) {
		var received = queueJobs(1).getFirst();
		when(processor.process(any(), any(), any(), any())).thenReturn(answered());
		when(completion.complete(anyLong(), any(), any(), any())).thenReturn(new Completion(true, 33L));
		var properties = properties(1, Duration.ofSeconds(1));
		configure(properties);
		var worker = new ChatJobWorker(stream, properties, runner, recovery);
		var failure = new DataAccessResourceFailureException("비공개 질문 secret-token");
		when(stream.read("test-worker", 1, null)).thenThrow(failure, failure)
				.thenReturn(List.of(received), List.of());

		try {
			worker.start();
			verify(stream, timeout(2_000)).ack(received.messageId());

			assertThat(output.getAll()).containsOnlyOnce("답변 작업 스트림을 읽지 못했습니다")
					.containsOnlyOnce("답변 작업 스트림 읽기가 복구되었습니다")
					.contains("consumer=test-worker", "이전예외=DataAccessResourceFailureException", "답변저장=true")
					.doesNotContain("비공개 질문", "secret-token");
		} finally {
			worker.stop();
		}
	}
}
