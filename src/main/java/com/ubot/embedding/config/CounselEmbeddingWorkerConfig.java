package com.ubot.embedding.config;

import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.worker.CounselEmbeddingWorker;
import com.ubot.global.config.AppRole;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(AppRole.RUNS_WORKER)
@ConditionalOnProperty(name = "app.counsel.embedding-worker.enabled", havingValue = "true", matchIfMissing = true)
public class CounselEmbeddingWorkerConfig {
	@Bean
	public CounselEmbeddingWorker counselEmbeddingWorker(
			StringRedisTemplate redis, EmbeddingClient embedding, ObjectMapper json,
			ObjectProvider<MeterRegistry> metrics) {
		// 지표 저장소가 없는 테스트 문맥에서는 전역 레지스트리(기록 안 함)를 씀
		return new CounselEmbeddingWorker(redis, embedding, json, metrics.getIfAvailable(() -> Metrics.globalRegistry));
	}
}
