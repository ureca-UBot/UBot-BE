package com.ubot.chat.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.job.dto.ChatRequestContext;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.service.ChatSubmissionService.SubmissionType;
import com.ubot.chat.job.stream.ChatJobStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@DisplayName("답변 작업 접수 지표 테스트")
class ChatSubmissionServiceMetricsTest {

	private final ChatJobRepository jobs = mock(ChatJobRepository.class);
	private final ChatJobStream stream = mock(ChatJobStream.class);
	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final ChatSubmissionService service = new ChatSubmissionService(jobs, stream, transactionManager, registry);

	@Test
	@DisplayName("접수 종류와 회원 여부를 태그로 접수 수를 센다")
	void countsSubmissionsByTypeAndUser() {
		when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
		when(jobs.saveAndFlush(any())).thenAnswer(call -> {
			ChatJob job = call.getArgument(0);
			ReflectionTestUtils.setField(job, "id", 1L);
			return job;
		});

		service.submit(SubmissionType.QUESTION, true, ChatSubmissionServiceMetricsTest::attempt, ChatRequestContext.EMPTY);
		service.submit(SubmissionType.QUESTION, false, ChatSubmissionServiceMetricsTest::attempt, ChatRequestContext.EMPTY);
		service.submit(SubmissionType.RETRY, true, ChatSubmissionServiceMetricsTest::attempt, ChatRequestContext.EMPTY);

		assertThat(submitted("question", "member")).isEqualTo(1);
		assertThat(submitted("question", "guest")).isEqualTo(1);
		assertThat(submitted("retry", "member")).isEqualTo(1);
		assertThat(registry.find("chat.jobs.submitted").tag("type", "research").counter()).isNull();
	}

	@Test
	@DisplayName("시도를 만들다 실패해 접수되지 않은 요청은 세지 않는다")
	void doesNotCountRejectedSubmission() {
		when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());

		assertThatThrownBy(() -> service.submit(SubmissionType.QUESTION, true, () -> {
			throw new IllegalStateException("검증 실패");
		}, ChatRequestContext.EMPTY)).isInstanceOf(IllegalStateException.class);

		assertThat(registry.find("chat.jobs.submitted").counter()).isNull();
	}

	private double submitted(String type, String user) {
		return registry.get("chat.jobs.submitted").tag("type", type).tag("user", user).counter().count();
	}

	private static AnswerAttemptsHistory attempt() {
		return AnswerAttemptsHistory.builder().id(10L).userId(1L).question("질문").attemptCount(1).status("PENDING").build();
	}
}
