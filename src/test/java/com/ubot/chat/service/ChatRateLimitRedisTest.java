package com.ubot.chat.service;

import com.ubot.chat.config.ChatRateLimitProperties;
import com.ubot.chat.exception.ChatException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 Redis에서 INCR·EXPIRE 스크립트가 윈도를 지키는지 확인합니다. 이 클래스만 쓰는 컨테이너입니다.
@DisplayName("회원 채팅 접수 제한 Redis 스크립트")
class ChatRateLimitRedisTest {
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
			.withExposedPorts(6379);
	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redis;

	private final ChatRateLimitProperties properties = new ChatRateLimitProperties();
	private final ChatRateLimitService service = new ChatRateLimitService(redis, properties);

	@BeforeAll
	static void startRedis() {
		REDIS.start();
		connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();
		redis = new StringRedisTemplate(connectionFactory);
	}

	@AfterAll
	static void stopRedis() {
		connectionFactory.destroy();
		REDIS.stop();
	}

	@BeforeEach
	void clear() {
		redis.delete(redis.keys("chat:rate:user:*"));
		properties.setMaxRequests(3);
	}

	@Test
	@DisplayName("윈도 안에서 한도까지 접수하고 그다음부터 거절하며, 다른 회원은 따로 센다")
	void countsWithinWindowPerUser() {
		for (int i = 0; i < 3; i++) {
			assertThatCode(() -> service.validateMemberRequest(1L)).doesNotThrowAnyException();
		}
		assertThatThrownBy(() -> service.validateMemberRequest(1L)).isInstanceOf(ChatException.class);

		assertThatCode(() -> service.validateMemberRequest(2L)).doesNotThrowAnyException();
		Long ttl = redis.getExpire(ChatRateLimitService.key(1L));
		assertThat(ttl).isBetween(1L, 60L);
	}

	@Test
	@DisplayName("윈도가 지나면 다시 접수한다")
	void resetsAfterWindow() throws InterruptedException {
		properties.setMaxRequests(1);
		properties.setWindow(Duration.ofSeconds(1));
		service.validateMemberRequest(1L);
		assertThatThrownBy(() -> service.validateMemberRequest(1L)).isInstanceOf(ChatException.class);

		Thread.sleep(1_200);

		assertThatCode(() -> service.validateMemberRequest(1L)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("만료 시간이 빠진 키가 남아 있어도 다시 만료 시간을 걸어 영구히 막지 않는다")
	void repairsMissingTtl() {
		redis.opsForValue().set(ChatRateLimitService.key(1L), "10");

		assertThatThrownBy(() -> service.validateMemberRequest(1L)).isInstanceOf(ChatException.class);

		assertThat(redis.getExpire(ChatRateLimitService.key(1L))).isBetween(1L, 60L);
	}
}
