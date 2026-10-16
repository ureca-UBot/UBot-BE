package com.ubot.chat.job.worker;

import com.ubot.chat.job.repository.ChatJobQueueRepository;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.service.ChatJobCompletionService;
import com.ubot.chat.job.stream.ChatJobResultStore;
import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatJobProcessor;
import com.ubot.global.config.AppRole;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Worker는 app.role이 worker·all일 때만 만듦
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(AppRole.RUNS_WORKER)
@EnableConfigurationProperties(ChatWorkerProperties.class)
public class ChatWorkerConfig {

	@Bean
	public ChatJobWorker chatJobWorker(
			ChatJobStream stream,
			ChatJobResultStore resultStore,
			ChatJobQueueRepository queueRepository,
			ChatJobRepository jobRepository,
			ChatJobCompletionService completionService,
			ChatJobProcessor jobProcessor,
			AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository,
			ChatWorkerProperties properties, MeterRegistry registry) {
		ChatJobWorker worker = new ChatJobWorker(stream, resultStore, queueRepository, jobRepository, completionService, jobProcessor,
				answerAttemptsHistoryRepository, properties);
		worker.observeWith(registry);
		return worker;
	}

	@Bean
	public HealthIndicator chatWorkerHealthIndicator(ChatJobWorker worker) {
		return () -> (worker.isOperational() ? Health.up() : Health.down())
				.withDetail("consumerRunning", worker.isOperational()).build();
	}
}
