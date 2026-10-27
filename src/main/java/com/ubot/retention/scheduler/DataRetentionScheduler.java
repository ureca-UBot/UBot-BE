package com.ubot.retention.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ubot.global.config.AppRole;
import com.ubot.telemap.service.TelemapRetentionService;
import com.ubot.telemap.service.TelemapRetentionService.RetentionResult;
import com.ubot.subscription.service.RoamingStatusService;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
/**
 * 보관 기간이 지난 공통 데이터와 통신 지도 데이터를 매일 정리합니다. 삭제 조건은 R__purge_expired_data.sql의 프로시저에 있습니다.
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
	private final TelemapRetentionService telemapRetentionService;
	private final RoamingStatusService roamingStatusService;
	private final Clock clock;
	private final RetryTemplate retryTemplate;

	@Autowired
	public DataRetentionScheduler(
			JdbcTemplate jdbcTemplate,
			TelemapRetentionService telemapRetentionService,
			RoamingStatusService roamingStatusService,
			Clock clock
	) {
		this(jdbcTemplate, telemapRetentionService, roamingStatusService, clock, new RetryTemplate(RetryPolicy.builder()
				.maxRetries(MAX_RETRIES)
				.delay(RETRY_DELAY)
				.build()));
	}

	DataRetentionScheduler(
			JdbcTemplate jdbcTemplate,
			TelemapRetentionService telemapRetentionService,
			RoamingStatusService roamingStatusService,
			Clock clock,
			RetryTemplate retryTemplate
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.telemapRetentionService = telemapRetentionService;
		this.roamingStatusService = roamingStatusService;
		this.clock = clock;
		this.retryTemplate = retryTemplate;
	}

	@Scheduled(cron = "${data-retention.purge-cron}", zone = "Asia/Seoul")
	@SchedulerLock(
			name = "dataRetentionPurge",
			lockAtMostFor = "PT30M",
			lockAtLeastFor = "PT10M"
	)
	public void purgeExpiredData() {
		updateRoamingStatuses();
		long startedAt = System.nanoTime();
		log.info("보관 기간이 지난 데이터 정리를 시작합니다.");
		purgeCommonData();
		purgeTelemapData();
		log.info("보관 데이터 정리 실행을 마쳤습니다: 처리시간={}ms", elapsedMillis(startedAt));
	}

	private void purgeCommonData() {
		try {
			retryTemplate.execute(() -> {
				jdbcTemplate.execute(PURGE_SQL);
				return null;
			});
			log.info("기존 보관 데이터를 정리했습니다.");
		} catch (RetryException exception) {
			log.error("기존 보관 데이터 정리에 실패했습니다: 재시도={}회", MAX_RETRIES, exception);
		}
	}

	private void purgeTelemapData() {
		try {
			RetentionResult result = retryTemplate.execute(
					() -> telemapRetentionService.purge(LocalDateTime.now(clock)));
			if (result.clusterDeletionSkipped()) {
				log.info("통신 지도 질문 대기열이 남아 있어 만료 클러스터 삭제를 건너뜁니다: pending={}",
						result.pendingQuestions());
			}
			log.info(
					"통신 지도 보존 데이터를 정리했습니다: 익명화알림={}, 익명화클러스터={}, 삭제클러스터={}",
					result.anonymizedAlerts(),
					result.anonymizedClusters(),
					result.deletedClusters()
			);
		} catch (RetryException exception) {
			log.error("통신 지도 보존 데이터 정리에 실패했습니다: 재시도={}회", MAX_RETRIES, exception);
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
