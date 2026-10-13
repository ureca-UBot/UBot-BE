package com.ubot.auth.service;

import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {
	static final int MAX_EMAIL_FAILURES = 5;
	static final int MAX_IP_FAILURES = 20;
	static final Duration LOCK_DURATION = Duration.ofMinutes(15);

	private final StringRedisTemplate redisTemplate;

	public void validateNotLocked(String email, String ip) {
		List<String> counts;
		try {
			counts = redisTemplate.opsForValue().multiGet(List.of(emailKey(email), ipKey(ip)));
		} catch (DataAccessException e) {
			log.warn("로그인 실패 횟수를 조회하지 못해 제한 없이 진행합니다", e);
			return;
		}
		if (counts == null) {
			return;
		}
		if (count(counts.get(0)) >= MAX_EMAIL_FAILURES || count(counts.get(1)) >= MAX_IP_FAILURES) {
			throw new UserException(UserErrorCode.LOGIN_TEMPORARILY_LOCKED);
		}
	}

	public void recordFailure(String email, String ip) {
		try {
			increment(emailKey(email));
			increment(ipKey(ip));
		} catch (DataAccessException e) {
			log.warn("로그인 실패 횟수를 저장하지 못했습니다", e);
		}
	}

	public void recordSuccess(String email) {
		try {
			redisTemplate.delete(emailKey(email));
		} catch (DataAccessException e) {
			log.warn("로그인 실패 횟수를 초기화하지 못했습니다", e);
		}
	}

	private void increment(String key) {
		Long count = redisTemplate.opsForValue().increment(key);
		if (Objects.equals(count, 1L)) {
			redisTemplate.expire(key, LOCK_DURATION);
		}
	}

	private int count(String value) {
		return value == null ? 0 : Integer.parseInt(value);
	}

	private String emailKey(String email) {
		return "login:fail:email:" + email;
	}

	private String ipKey(String ip) {
		return "login:fail:ip:" + ip;
	}
}
