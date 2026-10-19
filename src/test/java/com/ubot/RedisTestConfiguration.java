package com.ubot;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

// 스트림을 실제로 읽고 쓰는 테스트용 Redis(Compose와 같은 이미지)
// 컨테이너 하나를 모든 테스트 컨텍스트가 같이 씀. 컨텍스트 종료 때 멈추지 않게 빈 등록 없이 접속 정보만 넘김
// 데이터를 나누지 않으므로 각 테스트가 시작할 때 쓰는 키를 지움
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestConfiguration {
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
			.withExposedPorts(6379)
			.withLabel("com.ubot.test-redis", "true")
			.withReuse(false);

	@Bean
	DynamicPropertyRegistrar redisProperties() {
		REDIS.start();
		return registry -> {
			registry.add("spring.data.redis.host", REDIS::getHost);
			registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		};
	}
}
