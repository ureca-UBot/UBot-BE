package com.ubot.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class AsyncConfig {
	@Bean(destroyMethod = "close")
	public SimpleAsyncTaskExecutor chatExecutor() {
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("chat-answer-");
		// 고정 동시 처리 수 제한 없이 요청마다 별도 가상 스레드에서 처리합니다.
		executor.setVirtualThreads(true);
		executor.setTaskTerminationTimeout(150_000L);
		return executor;
	}
}
