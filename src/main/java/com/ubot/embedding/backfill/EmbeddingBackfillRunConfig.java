package com.ubot.embedding.backfill;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 백필만 하고 끝나는 실행(app.embedding-backfill.run=true)에서만 적용되는 설정입니다.
 * 배포 스크립트가 백엔드를 교체하기 전에 이 방식으로 띄웁니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = EmbeddingBackfillRunner.RUN_PROPERTY, havingValue = "true")
public class EmbeddingBackfillRunConfig {

	// 이 실행 중에는 기존 백엔드가 아직 서비스 중이므로 스키마를 바꾸지 않습니다.
	// 마이그레이션은 실제 백엔드가 뜰 때 적용합니다.
	@Bean
	public FlywayMigrationStrategy skipMigrationInBackfillRun() {
		return flyway -> {
		};
	}
}
