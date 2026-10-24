package com.ubot.chat.service;

import com.ubot.chat.config.ChatRateLimitProperties;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 회원의 채팅 접수 횟수를 사용자 ID별 고정 윈도로 셉니다. 한도를 넘으면 시도·작업을 만들기 전에 거절합니다.
 * 접근 토큰을 다시 발급해도 같은 사용자의 횟수는 이어지고, IP를 함께 쓰는 회원끼리는 서로 영향을 주지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRateLimitService {
	// 만료 시간이 빠진 키가 남으면 영원히 막히므로, TTL이 없으면 다시 설정합니다.
	static final RedisScript<Long> INCREMENT_WITH_EXPIRE = RedisScript.of(
			"local count = redis.call('INCR', KEYS[1]) "
					+ "if count == 1 or redis.call('TTL', KEYS[1]) == -1 then "
					+ "redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
					+ "return count",
			Long.class);

	private final StringRedisTemplate redisTemplate;
	private final ChatRateLimitProperties properties;

	public void validateMemberRequest(Long userId) {
		if (!properties.isEnabled()) {
			return;
		}
		Long count;
		try {
			count = redisTemplate.execute(INCREMENT_WITH_EXPIRE, List.of(key(userId)),
					String.valueOf(properties.getWindow().toSeconds()));
		} catch (DataAccessException e) {
			// 로그인 실패 제한과 같이 Redis 장애로 채팅 전체를 막지 않습니다.
			log.warn("회원 채팅 요청 수를 확인하지 못해 제한 없이 진행합니다: 사용자ID={}", userId, e);
			return;
		}
		if (count != null && count > properties.getMaxRequests()) {
			log.info("회원 채팅 요청 한도를 넘었습니다: 사용자ID={}, 요청수={}, 한도={}/{}초",
					userId, count, properties.getMaxRequests(), properties.getWindow().toSeconds());
			throw new ChatException(ChatErrorCode.MEMBER_RATE_LIMITED);
		}
	}

	static String key(Long userId) {
		return "chat:rate:user:" + userId;
	}
}
