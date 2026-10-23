package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(OutputCaptureExtension.class)
class ChatJobProcessorLoggingTest {

	private final ChatAnswerProcessor answers = mock(ChatAnswerProcessor.class);
	private final ChatJobProcessor processor = new ChatJobProcessor(answers);
	private final AnswerAttemptsHistory attempt = AnswerAttemptsHistory.builder()
			.id(42L).userId(1L).question("비공개 질문").attemptCount(1).status("PENDING").build();

	@Test
	void databaseFailureKeepsStorageCodeAndSafeCauseInWorkerLog(CapturedOutput output) {
		when(answers.generateAnswer(any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException(
				"INSERT INTO answers 비공개 질문", new IllegalStateException("secret-token")));
		var result = processor.process(attempt, null, () -> {}, List.of());
		assertThat(result.errorCode()).isEqualTo(ChatErrorCode.STORAGE_UNAVAILABLE);

		assertThat(output.getAll()).contains("시도ID=42", "오류코드=" + ChatErrorCode.STORAGE_UNAVAILABLE.getCode(),
				"예외종류=DataAccessResourceFailureException/IllegalStateException")
				.doesNotContain("INSERT INTO answers", "비공개 질문", "secret-token");
	}

	@Test
	void unexpectedFailureKeepsMaskedStackTraceWithoutMessages(CapturedOutput output) {
		when(answers.generateAnswer(any(), any(), any(), any())).thenThrow(
				new IllegalArgumentException("비공개 답변", new IllegalStateException("secret-token")));
		var result = processor.process(attempt, null, () -> {}, List.of());

		assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INTERNAL_ERROR);
		// 위치를 찾을 스택트레이스와 원인 연결은 남기고 메시지는 지움
		assertThat(output.getAll()).contains("시도ID=42", "오류코드=" + ChatErrorCode.INTERNAL_ERROR.getCode(),
				"예외종류=IllegalArgumentException/IllegalStateException",
				"java.lang.IllegalArgumentException (메시지 생략)", "Caused by: ",
				"java.lang.IllegalStateException (메시지 생략)", "at com.ubot.chat.service.ChatJobProcessor.process")
				.doesNotContain("비공개 답변", "secret-token");
	}
}
