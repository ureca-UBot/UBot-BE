package com.ubot.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ubot.auth.exception.JwtErrorCode;
import com.ubot.auth.exception.MyJwtException;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
	private final ReactiveStringRedisTemplate revocationReader = mock(ReactiveStringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ReactiveValueOperations<String, String> reads = mock(ReactiveValueOperations.class);
	private final AccessTokenRevocationService service = new AccessTokenRevocationService(redisTemplate, revocationReader, 600_000L,
			Duration.ofMillis(200));

	{
		when(redisTemplate.opsForValue()).thenReturn(values);
		when(revocationReader.opsForValue()).thenReturn(reads);
		when(reads.get(anyString())).thenReturn(Mono.empty());
	}

	@Test
	@DisplayName("로그아웃 시각을 접근 토큰 유효 시간만큼 저장한다")
	void storesRevocationForAccessTokenLifetime() {
		service.revokeAll(7L);

		verify(values).set(eq("auth:access-revoked-before:7"), anyString(), eq(Duration.ofMinutes(10)));
	}

	@Test
	@DisplayName("로그아웃 시각까지 밀리초 단위로 발급된 토큰은 막고, 이후에 발급된 토큰은 통과시킨다")
	void rejectsOnlyTokensIssuedBeforeRevocation() {
		when(reads.get("auth:access-revoked-before:7")).thenReturn(Mono.just("1000500"));

		assertThat(service.isRevoked(7L, Instant.ofEpochMilli(1000100))).isTrue();
		assertThat(service.isRevoked(7L, Instant.ofEpochMilli(1000500))).isTrue();
		assertThat(service.isRevoked(7L, Instant.ofEpochMilli(1000501))).isFalse();
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
		when(reads.get(anyString())).thenReturn(Mono.error(new RedisConnectionFailureException("down")));

		assertThat(service.isRevoked(7L, Instant.ofEpochSecond(999))).isFalse();
	}

	@Test
	@DisplayName("Redis가 응답하지 않으면 조회를 취소하고 로그아웃 여부를 모르므로 요청을 거부한다")
	void rejectsStalledLookupAfterCancellingIt() {
		AtomicBoolean cancelled = new AtomicBoolean();
		when(reads.get(anyString())).thenReturn(Mono.<String>never().doOnCancel(() -> cancelled.set(true)));

		long started = System.nanoTime();
		assertRevocationCheckUnavailable(() -> service.isRevoked(7L, Instant.ofEpochSecond(999)));

		assertThat(cancelled).isTrue();
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
	}

	@Test
	@DisplayName("조회 시간 초과 이후 Redis가 회복되면 차단 기록을 다시 적용한다")
	void checksRevocationAgainAfterRedisRecovers() {
		when(reads.get(anyString())).thenReturn(Mono.never()).thenReturn(Mono.just("1000500"));

		assertRevocationCheckUnavailable(() -> service.isRevoked(7L, Instant.ofEpochMilli(1000100)));
		assertThat(service.isRevoked(7L, Instant.ofEpochMilli(1000100))).isTrue();
	}

	private static void assertRevocationCheckUnavailable(ThrowingCallable lookup) {
		assertThatThrownBy(lookup).isInstanceOfSatisfying(MyJwtException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(JwtErrorCode.REVOCATION_CHECK_UNAVAILABLE));
	}
}
