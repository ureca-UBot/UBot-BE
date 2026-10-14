package com.ubot.auth.service;

import com.ubot.user.exception.UserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("로그인 실패 제한")
class LoginAttemptServiceTest {

	private static final String EMAIL = "user@example.com";
	private static final String IP = "203.0.113.1";

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
	private final LoginAttemptService service = new LoginAttemptService(redisTemplate);

	@BeforeEach
	void setUp() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
	}

	@Test
	@DisplayName("이메일 실패가 5회 미만이면 로그인을 허용한다")
	void allowsBelowEmailLimit() {
		when(valueOperations.multiGet(anyList())).thenReturn(List.of("4", "4"));

		assertThatCode(() -> service.validateNotLocked(EMAIL, IP)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("이메일 실패가 5회면 로그인을 막는다")
	void locksAtEmailLimit() {
		when(valueOperations.multiGet(anyList())).thenReturn(List.of("5", "5"));

		assertThatThrownBy(() -> service.validateNotLocked(EMAIL, IP))
				.isInstanceOf(UserException.class);
	}

	@Test
	@DisplayName("같은 IP의 실패가 20회면 다른 이메일도 막는다")
	void locksAtIpLimit() {
		when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(null, "20"));

		assertThatThrownBy(() -> service.validateNotLocked(EMAIL, IP))
				.isInstanceOf(UserException.class);
	}

	@Test
	@DisplayName("Redis에 연결할 수 없으면 로그인을 막지 않는다")
	void allowsWhenRedisIsDown() {
		when(valueOperations.multiGet(anyList())).thenThrow(new RedisConnectionFailureException("down"));

		assertThatCode(() -> service.validateNotLocked(EMAIL, IP)).doesNotThrowAnyException();
		assertThatCode(() -> service.recordFailure(EMAIL, IP)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("실패 횟수 증가와 만료 시간 설정을 한 번의 Redis 스크립트로 실행한다")
	void incrementsWithExpireAtomically() {
		service.recordFailure(EMAIL, IP);

		verify(redisTemplate).execute(LoginAttemptService.INCREMENT_WITH_EXPIRE,
				List.of("login:fail:email:" + EMAIL), "900");
		verify(redisTemplate).execute(LoginAttemptService.INCREMENT_WITH_EXPIRE,
				List.of("login:fail:ip:" + IP), "900");
		verify(valueOperations, never()).increment(anyString());
		verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
	}

	@Test
	@DisplayName("Redis에 실패 횟수를 저장할 수 없어도 예외를 던지지 않는다")
	void ignoresRedisFailureOnRecord() {
		when(redisTemplate.execute(eq(LoginAttemptService.INCREMENT_WITH_EXPIRE), anyList(), anyString()))
				.thenThrow(new RedisConnectionFailureException("down"));

		assertThatCode(() -> service.recordFailure(EMAIL, IP)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("로그인에 성공하면 이메일 실패 횟수를 지운다")
	void clearsEmailFailuresOnSuccess() {
		service.recordSuccess(EMAIL);

		verify(redisTemplate).delete("login:fail:email:" + EMAIL);
	}
}
