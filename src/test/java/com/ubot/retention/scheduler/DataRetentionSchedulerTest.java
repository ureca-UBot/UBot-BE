package com.ubot.retention.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("보관 기간 데이터 삭제 스케줄러 테스트")
class DataRetentionSchedulerTest {

	private static final String PURGE_SQL = "CALL purge_expired_data()";

	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final DataRetentionScheduler scheduler = new DataRetentionScheduler(
			jdbcTemplate,
			new RetryTemplate(RetryPolicy.builder().maxRetries(3).delay(Duration.ZERO).build()));

	@Test
	@DisplayName("스케줄 실행 시 삭제 프로시저를 한 번 호출한다")
	void callsPurgeProcedure() {
		scheduler.purgeExpiredData();

		verify(jdbcTemplate).execute(PURGE_SQL);
	}

	@Test
	@DisplayName("실패하면 다시 시도하고 성공하면 멈춘다")
	void retriesUntilSuccess() {
		doThrow(new QueryTimeoutException("일시 오류"))
				.doThrow(new QueryTimeoutException("일시 오류"))
				.doNothing()
				.when(jdbcTemplate).execute(PURGE_SQL);

		scheduler.purgeExpiredData();

		verify(jdbcTemplate, times(3)).execute(PURGE_SQL);
	}

	@Test
	@DisplayName("재시도 3회까지 모두 실패하면 예외를 밖으로 던지지 않고 끝낸다")
	void stopsAfterThreeRetries() {
		doThrow(new QueryTimeoutException("계속 실패")).when(jdbcTemplate).execute(PURGE_SQL);

		assertThatCode(scheduler::purgeExpiredData).doesNotThrowAnyException();

		verify(jdbcTemplate, times(4)).execute(PURGE_SQL);
	}
}
