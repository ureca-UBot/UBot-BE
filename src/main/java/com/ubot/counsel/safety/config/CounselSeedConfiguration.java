package com.ubot.counsel.safety.config;

import com.ubot.counsel.safety.service.SafetyProfileService;
import com.ubot.global.config.AppRole;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(AppRole.RUNS_SCHEDULERS)
@ConditionalOnProperty(name = "counsel.seed-on-start", havingValue = "true")
public class CounselSeedConfiguration {
	@Bean
	public ApplicationRunner counselSeedRunner(SafetyProfileService profiles) {
		return args -> profiles.seedProfile();
	}
}
