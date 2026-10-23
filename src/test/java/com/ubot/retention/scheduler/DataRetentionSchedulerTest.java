package com.ubot.retention.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ubot.telemap.service.TelemapRetentionService;
import com.ubot.telemap.service.TelemapRetentionService.RetentionResult;
import com.ubot.subscription.service.RoamingStatusService;

@DisplayName("보관 기간 데이터 삭제 스케줄러 테스트")
class DataRetentionSchedulerTest {

	private static final String PURGE_SQL = "CALL purge_expired_data()";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final TelemapRetentionService telemapRetentionService = mock(TelemapRetentionService.class);
	private final RoamingStatusService roamingStatusService = mock(RoamingStatusService.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
	private final DataRetentionScheduler scheduler = new DataRetentionScheduler(
			jdbcTemplate,
			telemapRetentionService,
			roamingStatusService,
			clock,
			new RetryTemplate(RetryPolicy.builder().maxRetries(3).delay(Duration.ZERO).build()));

	@BeforeEach
	void setUp() {
		when(telemapRetentionService.purge(NOW)).thenReturn(new RetentionResult(0, 0, 0, 0));
	}

	@Test
	@DisplayName("스케줄 실행 시 기존 프로시저와 통신 지도 보존 작업을 호출한다")
	void callsRetentionTasks() {
		when(telemapRetentionService.purge(NOW)).thenReturn(new RetentionResult(1, 2, 3, 0));

		scheduler.purgeExpiredData();

		verify(jdbcTemplate).execute(PURGE_SQL);
		verify(telemapRetentionService).purge(NOW);
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
		when(telemapRetentionService.purge(NOW)).thenReturn(new RetentionResult(0, 0, 0, 0));

		scheduler.purgeExpiredData();

		verify(jdbcTemplate, times(3)).execute(PURGE_SQL);
		verify(telemapRetentionService).purge(NOW);
	}

	@Test
	@DisplayName("재시도 한도를 모두 소진하면 예외를 밖으로 전파하지 않는다")
	void stopsAfterThreeRetries() {
		doThrow(new QueryTimeoutException("계속 실패")).when(jdbcTemplate).execute(PURGE_SQL);
		when(telemapRetentionService.purge(NOW)).thenReturn(new RetentionResult(0, 0, 0, 0));

		assertThatCode(scheduler::purgeExpiredData).doesNotThrowAnyException();

		verify(jdbcTemplate, times(4)).execute(PURGE_SQL);
		verify(telemapRetentionService).purge(NOW);
	}

	@Test
	@DisplayName("통신 지도 보존 작업 실패는 기존 보존 작업을 다시 실행하지 않는다")
	void retriesTelemapRetentionIndependently() {
		when(telemapRetentionService.purge(NOW))
				.thenThrow(new QueryTimeoutException("계속 실패"));

		assertThatCode(scheduler::purgeExpiredData).doesNotThrowAnyException();

		verify(jdbcTemplate).execute(PURGE_SQL);
		verify(telemapRetentionService, times(4)).purge(NOW);
	}
}
