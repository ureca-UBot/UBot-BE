package com.ubot.guardreview.scheduler;

import com.ubot.global.config.AppRole;
import com.ubot.guardreview.service.ReviewService;
import com.ubot.guardreview.service.ReviewRetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnExpression(AppRole.RUNS_SCHEDULERS)
public class ReviewScheduler {
	private final ReviewService reviews;
	private final ReviewRetentionService retention;
	@Scheduled(fixedDelay = 60000)
	@SchedulerLock(name = "guardReviewSchedule", lockAtMostFor = "PT1M")
	public void tick() {
		try {
			reviews.scheduleDue();
		} catch (RuntimeException e) {
			log.warn("검토 주기를 확인하지 못했습니다: 오류유형={}", e.getClass().getSimpleName());
		}
	}
	@Scheduled(cron = "0 15 4 * * *", zone = "Asia/Seoul")
	@SchedulerLock(name = "guardReviewRetention", lockAtMostFor = "PT5M")
	public void cleanup() {
		try {
			retention.cleanup();
		} catch (RuntimeException e) {
			log.warn("검토 기록 정리를 다음 주기에 다시 시도합니다: 오류유형={}", e.getClass().getSimpleName());
		}
	}
}
