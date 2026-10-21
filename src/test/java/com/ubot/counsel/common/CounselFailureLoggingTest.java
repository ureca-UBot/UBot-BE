package com.ubot.counsel.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.message.repository.MessageRepository;
import com.ubot.counsel.message.service.MessageService;
import com.ubot.counsel.session.service.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class CounselFailureLoggingTest {

	@Test
	void failuresExposeCodeAndClassWithoutSensitiveSqlOrText(CapturedOutput output) {
		var handler = new CounselExceptionHandler();
		var databaseFailure = new DataAccessResourceFailureException("비밀 상담 원문 INSERT INTO messages");
		assertThat(handler.handleDatabaseException(databaseFailure).getStatusCode().value()).isEqualTo(503);
		assertThat(handler.handleUnexpectedException(new IllegalStateException("비밀 상담 원문")).getStatusCode().value()).isEqualTo(500);

		var config = mock(CounselProperties.class);
		when(config.enabled()).thenReturn(true);
		var tx = mock(TransactionTemplate.class);
		doThrow(databaseFailure).when(tx).execute(org.mockito.ArgumentMatchers.any());
		new SessionService(null, tx, config, null, null, null).maintainSessions();
		var messages = mock(MessageRepository.class);
		when(messages.stale(0)).thenThrow(databaseFailure);
		new MessageService(messages, null, null, null, tx, config, null).recoverPending();

		assertThat(output.getAll()).contains("COUNSEL-011", "COUNSEL-012", "DataAccessResourceFailureException", "IllegalStateException",
				"상담 배정 정리 실패", "상담 대기 메시지 정리 실패").doesNotContain("비밀 상담 원문", "INSERT INTO messages");
	}
}
