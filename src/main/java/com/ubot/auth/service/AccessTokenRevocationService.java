package com.ubot.auth.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AccessTokenRevocationService {
	private final StringRedisTemplate redisTemplate;
	private final ReactiveStringRedisTemplate revocationReader;
	private final Duration accessTokenLifetime;
	private final Duration lookupTimeout;

	public AccessTokenRevocationService(
			StringRedisTemplate redisTemplate,
			ReactiveStringRedisTemplate revocationReader,
			@Value("${app.jwt.access-token-expiration-millis}") Long accessTokenExpirationMillis,
			@Value("${app.jwt.revocation-lookup-timeout:200ms}") Duration lookupTimeout
	) {
		this.redisTemplate = redisTemplate;
		this.revocationReader = revocationReader;
		this.accessTokenLifetime = Duration.ofMillis(accessTokenExpirationMillis);
		this.lookupTimeout = lookupTimeout;
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
			// Redis 재연결 대기가 인증 요청을 붙잡지 않도록 이 조회에만 제한을 둠. 제한이 지나면 구독을 취소함
			revokedAt = revocationReader.opsForValue().get(key(userId))
					.timeout(lookupTimeout, Mono.error(() -> new QueryTimeoutException("접근 토큰 차단 조회 시간 초과")))
					.block();
		} catch (QueryTimeoutException e) {
			// Redis 장애와 같은 정책으로 차단 없이 진행함. 느려질 때마다 요청이 실패하지 않게 함
			// 매 요청마다 남으므로 스택트레이스 없이 기록함
			log.warn("접근 토큰 차단 정보를 제한 시간 안에 확인하지 못해 차단 없이 진행합니다: 사용자ID={}, 제한시간={}ms",
					userId, lookupTimeout.toMillis());
			return false;
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
