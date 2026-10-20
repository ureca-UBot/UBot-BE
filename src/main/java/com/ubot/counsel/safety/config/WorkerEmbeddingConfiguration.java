package com.ubot.counsel.safety.config;

import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.safety.client.WorkerEmbeddingTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "counsel.guard.transport", havingValue = "worker", matchIfMissing = true)
public class WorkerEmbeddingConfiguration {
	@Bean
	public WorkerEmbeddingTransport workerEmbeddingTransport(
			StringRedisTemplate redis, CounselProperties properties, ObjectMapper json) {
		return new WorkerEmbeddingTransport(redis, properties.guard(), json);
	}
}
