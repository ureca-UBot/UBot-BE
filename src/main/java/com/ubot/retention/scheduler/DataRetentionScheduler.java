package com.ubot.retention.scheduler;

import java.time.Duration;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.ubot.global.config.AppRole;
import com.ubot.subscription.service.RoamingStatusService;

/**
 * 보관 기간이 지난 데이터를 매일 지웁니다. 삭제 조건은 R__purge_expired_data.sql의 프로시저에 있습니다.
 * 날짜로 바뀌는 로밍 상태도 하루 한 번이면 되므로 삭제 전에 함께 바꿉니다({@link RoamingStatusService}).
 *
 * <p>프로시저가 1,000건마다 COMMIT 하므로 이 메서드에 {@code @Transactional}을 붙이면 안 됩니다.
 * 트랜잭션 안에서 CALL 하면 PostgreSQL이 "invalid transaction termination"으로 실패시킵니다.
 */
@Component
@ConditionalOnExpression(AppRole.RUNS_SCHEDULERS)
@Slf4j
public class DataRetentionScheduler {
	private static final String PURGE_SQL = "CALL purge_expired_data()";
	private static final int MAX_RETRIES = 3;
	private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

	private final JdbcTemplate jdbcTemplate;
	private final RoamingStatusService roamingStatusService;
	private final RetryTemplate retryTemplate;

	@Autowired
	public DataRetentionScheduler(JdbcTemplate jdbcTemplate, RoamingStatusService roamingStatusService) {
		this(jdbcTemplate, roamingStatusService, new RetryTemplate(RetryPolicy.builder()
				.maxRetries(MAX_RETRIES)
				.delay(RETRY_DELAY)
				.build()));
	}

	DataRetentionScheduler(JdbcTemplate jdbcTemplate, RoamingStatusService roamingStatusService,
			RetryTemplate retryTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.roamingStatusService = roamingStatusService;
		this.retryTemplate = retryTemplate;
	}

	// 서버가 여러 대여도 한 번만 실행합니다.
	// lockAtLeastFor: 시계가 조금 늦은 다른 서버가 작업이 끝난 직후 다시 실행하지 않도록 락을 최소 10분 유지합니다.
	// lockAtMostFor: 최대 4번 시도와 재시도 대기를 덮는 시간. lockAtLeastFor보다 짧으면 ShedLock이 실패합니다.
	@Scheduled(cron = "${data-retention.purge-cron}", zone = "Asia/Seoul")
	@SchedulerLock(
			name = "dataRetentionPurge",
			lockAtMostFor = "PT30M",
			lockAtLeastFor = "PT10M"
	)
	public void purgeExpiredData() {
		updateRoamingStatuses();
		long startedAt = System.nanoTime();
		log.info("보관 기간이 지난 데이터 삭제를 시작합니다.");
		try {
			// 프로시저는 이미 지운 데이터를 다시 지우지 않으므로, 중간에 실패해도 재시도가 남은 것부터 이어서 지웁니다.
			retryTemplate.execute(() -> {
				jdbcTemplate.execute(PURGE_SQL);
				return null;
			});
			log.info("보관 기간이 지난 데이터 삭제를 완료했습니다: 처리시간={}ms", elapsedMillis(startedAt));
		} catch (RetryException exception) {
			log.error("보관 기간이 지난 데이터 삭제에 실패했습니다: 재시도={}회, 처리시간={}ms",
					MAX_RETRIES, elapsedMillis(startedAt), exception);
		}
	}

	// 로밍 상태 전환이 실패해도 삭제는 이어서 합니다. 다음 날 실행에서 다시 맞춰집니다.
	private void updateRoamingStatuses() {
		try {
			roamingStatusService.updateStatuses(LocalDate.now());
		} catch (RuntimeException exception) {
			log.error("로밍 상태 전환에 실패했습니다.", exception);
		}
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
