package com.ubot.auth.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AccessTokenRevocationService {
	private final StringRedisTemplate redisTemplate;
	private final Duration accessTokenLifetime;

	public AccessTokenRevocationService(
			StringRedisTemplate redisTemplate,
			@Value("${app.jwt.access-token-expiration-millis}") Long accessTokenExpirationMillis
	) {
		this.redisTemplate = redisTemplate;
		this.accessTokenLifetime = Duration.ofMillis(accessTokenExpirationMillis);
	}

	public void revokeAll(Long userId) {
		try {
			redisTemplate.opsForValue().set(
					key(userId), String.valueOf(Instant.now().toEpochMilli()), accessTokenLifetime);
		} catch (DataAccessException e) {
			log.warn("접근 토큰 차단 정보를 저장하지 못했습니다: 사용자ID={}", userId, e);
		}
	}

	public boolean isRevoked(Long userId, Instant issuedAt) {
		if (issuedAt == null) {
			return false;
		}
		String revokedAt;
		try {
			revokedAt = redisTemplate.opsForValue().get(key(userId));
		} catch (DataAccessException e) {
			log.warn("접근 토큰 차단 정보를 조회하지 못해 차단 없이 진행합니다: 사용자ID={}", userId, e);
			return false;
		}
		return revokedAt != null && issuedAt.toEpochMilli() <= Long.parseLong(revokedAt);
	}

	private String key(Long userId) {
		return "auth:access-revoked-before:" + userId;
	}
}
