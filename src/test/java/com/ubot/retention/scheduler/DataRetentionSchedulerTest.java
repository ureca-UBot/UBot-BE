package com.ubot.retention.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ubot.subscription.service.RoamingStatusService;

@DisplayName("보관 기간 데이터 삭제 스케줄러 테스트")
class DataRetentionSchedulerTest {

	private static final String PURGE_SQL = "CALL purge_expired_data()";

	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final RoamingStatusService roamingStatusService = mock(RoamingStatusService.class);
	private final DataRetentionScheduler scheduler = new DataRetentionScheduler(
			jdbcTemplate,
			roamingStatusService,
			new RetryTemplate(RetryPolicy.builder().maxRetries(3).delay(Duration.ZERO).build()));

	@Test
	@DisplayName("스케줄 실행 시 삭제 프로시저를 한 번 호출한다")
	void callsPurgeProcedure() {
		scheduler.purgeExpiredData();

		verify(jdbcTemplate).execute(PURGE_SQL);
	}

	@Test
	@DisplayName("삭제 전에 오늘 날짜로 로밍 상태를 바꾼다")
	void updatesRoamingStatusesBeforePurge() {
		scheduler.purgeExpiredData();

		InOrder order = inOrder(roamingStatusService, jdbcTemplate);
		order.verify(roamingStatusService).updateStatuses(LocalDate.now());
		order.verify(jdbcTemplate).execute(PURGE_SQL);
	}

	@Test
	@DisplayName("로밍 상태 전환이 실패해도 삭제는 이어서 한다")
	void purgesEvenIfRoamingStatusUpdateFails() {
		doThrow(new QueryTimeoutException("로밍 전환 실패")).when(roamingStatusService).updateStatuses(any());

		assertThatCode(scheduler::purgeExpiredData).doesNotThrowAnyException();

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
