package com.ubot.auth.service;

import com.ubot.auth.entity.RefreshToken;
import com.ubot.auth.repository.RefreshTokenRepository;
import com.ubot.auth.util.RefreshTokenGenerator;
import com.ubot.auth.exception.JwtErrorCode;
import com.ubot.auth.exception.MyJwtException;
import com.ubot.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {
	private static final Duration REUSE_GRACE_PERIOD = Duration.ofSeconds(10);

	private final RefreshTokenRepository refreshTokenRepository;
	private final RefreshTokenGenerator refreshTokenGenerator;
	private final AccessTokenRevocationService accessTokenRevocationService;

	@Value("${app.jwt.refresh-token-expiration-days}")
	private Long refreshTokenExpirationDays;

	@Transactional
	public String issue(User user){
		String token = refreshTokenGenerator.generateRefreshToken();
		String tokenHash = refreshTokenGenerator.hash(token);
		LocalDateTime expiredAt = LocalDateTime.now().plusDays(refreshTokenExpirationDays);

		Optional<RefreshToken> optRefreshToken = refreshTokenRepository.findByUserId(user.getId());

		if(optRefreshToken.isPresent()){
			optRefreshToken.get().reissue(tokenHash, expiredAt);
		}
		else {
			try {
				refreshTokenRepository.save(
						RefreshToken.builder()
								.user(user)
								.token(tokenHash)
								.expiredAt(expiredAt)
								.build()
				);
			} catch(DataIntegrityViolationException e){
				throw new MyJwtException(JwtErrorCode.DUPLICATED_CREATE_REFRESH_TOKEN);
			}
		}
		return token;
	}

	@Transactional
	public String rotate(RefreshToken refreshToken){
		String token = refreshTokenGenerator.generateRefreshToken();
		LocalDateTime now = LocalDateTime.now();
		refreshToken.rotate(refreshTokenGenerator.hash(token), now.plusDays(refreshTokenExpirationDays), now);
		return token;
	}

	public RefreshToken getRefreshToken(String token){
		String tokenHash = refreshTokenGenerator.hash(token);
		Optional<RefreshToken> current = refreshTokenRepository.findByToken(tokenHash);
		if (current.isPresent()) {
			return current.get();
		}

		RefreshToken rotated = refreshTokenRepository.findByPreviousToken(tokenHash)
				.orElseThrow(() -> new MyJwtException(JwtErrorCode.REFRESH_TOKEN_NOT_FOUND));
		if (rotated.getRotatedAt() != null
				&& rotated.getRotatedAt().isAfter(LocalDateTime.now().minus(REUSE_GRACE_PERIOD))) {
			throw new MyJwtException(JwtErrorCode.REFRESH_TOKEN_NOT_FOUND);
		}

		log.warn("교체된 리프레시 토큰이 다시 사용되어 사용자의 토큰을 모두 무효화합니다: 사용자ID={}", rotated.getUser().getId());
		refreshTokenRepository.delete(rotated);
		accessTokenRevocationService.revokeAll(rotated.getUser().getId());
		throw new MyJwtException(JwtErrorCode.REFRESH_TOKEN_REUSED);
	}

	@Transactional
	public void deleteByUserId(Long userId){
		refreshTokenRepository.deleteByUserId(userId);
	}
}
