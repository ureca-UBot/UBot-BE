package com.ubot.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import com.ubot.auth.exception.JwtErrorCode;
import com.ubot.auth.exception.MyJwtException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Redis 장애 중 접근 토큰 차단 조회")
class AccessTokenRevocationRedisIntegrationTest {
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
			.withExposedPorts(6379).withLabel("com.ubot.test-redis", "true");
	private static LettuceConnectionFactory connection;
	private static StringRedisTemplate redis;
	private static AccessTokenRevocationService service;

	@BeforeAll
	static void startRedis() {
		REDIS.start();
		connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connection.afterPropertiesSet();
		connection.start();
		redis = new StringRedisTemplate(connection);
		service = new AccessTokenRevocationService(redis, new ReactiveStringRedisTemplate(connection), 600_000L,
				Duration.ofMillis(200));
	}

	@AfterAll
	static void stopRedis() {
		if (connection != null) connection.destroy();
		REDIS.stop();
	}

	@Test
	@DisplayName("Redis 응답이 멈추면 빠르게 거부하고 복구 후에는 기존 차단 기록을 적용한다")
	void rejectsPausedRedisLookupQuicklyAndResumesRevocationWithoutChangingOtherCommands() {
		redis.opsForValue().set("auth:access-revoked-before:7", "1000500");
		assertThat(service.isRevoked(7L, Instant.ofEpochMilli(1000100))).isTrue();
		Duration originalTimeout = connection.getClientConfiguration().getCommandTimeout();
		REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
		try {
			long started = System.nanoTime();
			assertThatThrownBy(() -> service.isRevoked(7L, Instant.ofEpochMilli(1000100)))
					.isInstanceOfSatisfying(MyJwtException.class, exception -> assertThat(exception.getErrorCode())
							.isEqualTo(JwtErrorCode.REVOCATION_CHECK_UNAVAILABLE));
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
		} finally {
			REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
		}
		assertThat(service.isRevoked(7L, Instant.ofEpochMilli(1000100))).isTrue();
		assertThat(service.isRevoked(7L, Instant.ofEpochMilli(1000501))).isFalse();
		assertThat(connection.getClientConfiguration().getCommandTimeout()).isEqualTo(originalTimeout);

		long started = System.nanoTime();
		assertThat(redis.opsForList().leftPop("worker-blocking-read", 1, TimeUnit.SECONDS)).isNull();
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofMillis(800));
	}
}
