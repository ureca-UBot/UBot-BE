package com.ubot.counsel.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ubot.common.SchedulerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class CounselSeedSchedulerTest {
	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(SchedulerConfig.class)
			.withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));

	@Test
	void normalApplicationStillRegistersSchedulers() {
		context.run(app -> assertThat(app).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
	}

	@Test
	void seedOnlyApplicationDoesNotRegisterAnyScheduledMethods() {
		context.withPropertyValues("counsel.seed-only=true")
				.run(app -> assertThat(app).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
	}
}
