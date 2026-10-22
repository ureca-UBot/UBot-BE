package com.ubot.counsel.common.config;

import com.ubot.global.config.AppRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(AppRole.RUNS_SCHEDULERS)
@ConditionalOnProperty(name = "counsel.seed-only", havingValue = "false", matchIfMissing = true)
public class CounselIndexSchedulerConfiguration {
	// 색인 중 워커 대기가 공통 알림·정리 스케줄러를 막지 않게 함
	@Bean(defaultCandidate = false)
	public ThreadPoolTaskScheduler counselIndexScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("counsel-index-");
		return scheduler;
	}
}
