package com.ubot.chat.service;

import com.ubot.chat.config.ChatRateLimitProperties;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("회원 채팅 접수 제한")
class ChatRateLimitServiceTest {
	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final ChatRateLimitProperties properties = new ChatRateLimitProperties();
	private final ChatRateLimitService service = new ChatRateLimitService(redisTemplate, properties);

	@Test
	@DisplayName("윈도 안의 요청 수가 한도와 같을 때까지는 접수한다")
	void allowsUpToLimit() {
		countIs(30L);

		assertThatCode(() -> service.validateMemberRequest(7L)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("한도를 넘으면 429 CHAT-024로 거절한다")
	void rejectsOverLimit() {
		countIs(31L);

		assertThatThrownBy(() -> service.validateMemberRequest(7L))
				.isInstanceOfSatisfying(ChatException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(ChatErrorCode.MEMBER_RATE_LIMITED);
					assertThat(e.getErrorCode().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
				});
	}

	@Test
	@DisplayName("사용자 ID별 키와 윈도 초를 한 번의 Redis 스크립트로 보낸다")
	void countsPerUserWithWindowSeconds() {
		properties.setWindow(Duration.ofMinutes(2));
		countIs(1L);

		service.validateMemberRequest(7L);

		verify(redisTemplate).execute(ChatRateLimitService.INCREMENT_WITH_EXPIRE,
				List.of("chat:rate:user:7"), "120");
	}

	@Test
	@DisplayName("Redis에 연결할 수 없으면 제한 없이 접수한다")
	void allowsWhenRedisIsDown() {
		when(redisTemplate.execute(eq(ChatRateLimitService.INCREMENT_WITH_EXPIRE), anyList(), anyString()))
				.thenThrow(new RedisConnectionFailureException("down"));

		assertThatCode(() -> service.validateMemberRequest(7L)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("꺼져 있으면 Redis를 부르지 않는다")
	void skipsWhenDisabled() {
		properties.setEnabled(false);

		service.validateMemberRequest(7L);

		verifyNoInteractions(redisTemplate);
	}

	@Test
	@DisplayName("한도가 1보다 작거나 윈도가 1초보다 짧으면 기동하지 않는다")
	void rejectsInvalidSettings() {
		ChatRateLimitProperties zeroMax = new ChatRateLimitProperties();
		zeroMax.setMaxRequests(0);
		ChatRateLimitProperties shortWindow = new ChatRateLimitProperties();
		shortWindow.setWindow(Duration.ofMillis(500));

		assertThatThrownBy(zeroMax::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(shortWindow::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
		assertThatCode(new ChatRateLimitProperties()::afterPropertiesSet).doesNotThrowAnyException();
	}

	private void countIs(long count) {
		when(redisTemplate.execute(eq(ChatRateLimitService.INCREMENT_WITH_EXPIRE), anyList(), anyString()))
				.thenReturn(count);
	}
}
