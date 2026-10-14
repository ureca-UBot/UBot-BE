package com.ubot.auth.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JWT 발급 시각")
class JwtUtilTest {
	private final JwtUtil jwtUtil = new JwtUtil(
			Base64.getEncoder().encodeToString("test-secret-key-for-jwt-util-0123456789".getBytes()), 600_000L);

	@Test
	@DisplayName("접근 토큰의 발급 시각을 밀리초 단위로 읽는다")
	void readsIssuedAtInMilliseconds() {
		User user = mock(User.class);
		when(user.getId()).thenReturn(1L);
		when(user.getRole()).thenReturn(UserRole.USER);

		Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		Instant issuedAt = jwtUtil.getIssuedAt(jwtUtil.createAccessToken(user));
		Instant after = Instant.now();

		assertThat(issuedAt).isBetween(before, after);
	}
}
