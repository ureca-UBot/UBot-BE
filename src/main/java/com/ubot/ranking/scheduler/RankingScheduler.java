package com.ubot.ranking.scheduler;

import com.ubot.ranking.service.RankingService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
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
		rankingService.refreshRanking();
	}
}
