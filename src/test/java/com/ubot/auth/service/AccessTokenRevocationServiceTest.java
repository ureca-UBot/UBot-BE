package com.ubot.auth.service;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("로그아웃된 접근 토큰 차단")
class AccessTokenRevocationServiceTest {
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> values = mock(ValueOperations.class);
	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final AccessTokenRevocationService service = new AccessTokenRevocationService(redisTemplate, 600_000L);

	{
		when(redisTemplate.opsForValue()).thenReturn(values);
	}

	@Test
	@DisplayName("로그아웃 시각을 접근 토큰 유효 시간만큼 저장한다")
	void storesRevocationForAccessTokenLifetime() {
		service.revokeAll(7L);

		verify(values).set(eq("auth:access-revoked-before:7"), anyString(), eq(Duration.ofMinutes(10)));
	}

	@Test
	@DisplayName("로그아웃 전에 발급된 토큰만 막고, 이후에 발급된 토큰은 통과시킨다")
	void rejectsOnlyTokensIssuedBeforeRevocation() {
		when(values.get("auth:access-revoked-before:7")).thenReturn("1000");

		assertThat(service.isRevoked(7L, Instant.ofEpochSecond(999))).isTrue();
		assertThat(service.isRevoked(7L, Instant.ofEpochSecond(1000))).isFalse();
		assertThat(service.isRevoked(7L, Instant.ofEpochSecond(1001))).isFalse();
	}

	@Test
	@DisplayName("로그아웃 기록이 없거나 발급 시각이 없으면 막지 않는다")
	void allowsWhenNoRevocation() {
		assertThat(service.isRevoked(7L, Instant.ofEpochSecond(999))).isFalse();
		assertThat(service.isRevoked(7L, null)).isFalse();
	}

	@Test
	@DisplayName("Redis에 연결할 수 없으면 요청을 막지 않는다")
	void allowsWhenRedisIsDown() {
		when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

		assertThat(service.isRevoked(7L, Instant.ofEpochSecond(999))).isFalse();
	}
}
