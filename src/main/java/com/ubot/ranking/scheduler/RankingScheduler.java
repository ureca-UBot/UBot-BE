package com.ubot.ranking.scheduler;

import com.ubot.ranking.service.RankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class RankingScheduler {
	private final RankingService rankingService;

	@Scheduled(
			fixedRateString = "${ranking.refresh-scheduler-interval-minutes}",
			timeUnit = TimeUnit.MINUTES)
	@SchedulerLock(
			name = "rankingRefresh",
			lockAtMostFor = "PT10M"
	)
	public void refreshRanking(){
		long startedAt = System.nanoTime();
		log.info("랭킹 갱신 작업을 시작합니다.");
		try {
			rankingService.refreshRanking();
			log.info("랭킹 갱신 작업을 완료했습니다: 처리시간={}ms", elapsedMillis(startedAt));
		} catch (RuntimeException exception) {
			log.error("랭킹 갱신 작업에 실패했습니다: 처리시간={}ms", elapsedMillis(startedAt), exception);
			throw exception;
		}
	}

	private long elapsedMillis(long startedAt) {
		return (System.nanoTime() - startedAt) / 1_000_000;
	}
}
