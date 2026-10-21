package com.ubot.counsel.safety.config;

import com.ubot.counsel.safety.service.SafetyProfileService;
import com.ubot.global.config.AppRole;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(AppRole.RUNS_SCHEDULERS)
@ConditionalOnProperty(name = "counsel.seed-on-start", havingValue = "true")
@Slf4j
public class CounselSeedConfiguration {
	public static final int EXIT_COMPLETED = 0;
	public static final int EXIT_FAILED = 1;
	public static final int EXIT_PENDING_MIGRATIONS = 3;

	// 기존 API가 서비스 중일 수 있으므로 일회성 실행에서는 스키마를 바꾸지 않음
	@Bean
	@ConditionalOnProperty(name = "counsel.seed-only", havingValue = "true")
	public FlywayMigrationStrategy skipMigrationInCounselSeedRun() {
		return flyway -> {
		};
	}

	@Bean
	public ApplicationRunner counselSeedRunner(Flyway flyway, SafetyProfileService profiles,
			ConfigurableApplicationContext context, @Value("${counsel.seed-only:false}") boolean seedOnly,
			@Value("${counsel.seed-check-only:false}") boolean checkOnly) {
		return args -> {
			if (seedOnly) {
				int exitCode = seed(flyway, profiles, checkOnly);
				System.exit(SpringApplication.exit(context, () -> exitCode));
				return;
			}
			profiles.seedProfile();
			profiles.verifyReady();
		};
	}

	int seed(Flyway flyway, SafetyProfileService profiles, boolean checkOnly) {
		try {
			int pending = flyway.info().pending().length;
			if (pending > 0) {
				log.warn("미적용 마이그레이션 {}건으로 상담 색인을 건너뜁니다. API 교체 후 다시 실행하세요.", pending);
				return EXIT_PENDING_MIGRATIONS;
			}
			// validate()는 미적용 항목도 오류로 처리하므로 전용 종료 코드 판정 뒤에 실행함
			flyway.validate();
			if (!checkOnly) {
				profiles.seedProfile();
				profiles.verifyReady();
			}
			return EXIT_COMPLETED;
		} catch (Exception exception) {
			log.error("상담 색인 실행 실패: code=COUNSEL_SEED_FAILED, exception={}", exception.getClass().getSimpleName());
			return EXIT_FAILED;
		}
	}
}
