package com.ubot.auth.service;

import com.ubot.auth.entity.RefreshToken;
import com.ubot.auth.repository.RefreshTokenRepository;
import com.ubot.auth.util.RefreshTokenGenerator;
import com.ubot.common.ErrorCode;
import com.ubot.common.exception.MyJwtException;
import com.ubot.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

	private final RefreshTokenRepository refreshTokenRepository;
	private final RefreshTokenGenerator refreshTokenGenerator;

	@Value("${app.jwt.refresh-token-expiration-days}")
	private Long refreshTokenExpirationDays;

	@Transactional
	public String createOrUpdate(User user){
		String token = refreshTokenGenerator.generateRefreshToken();

		LocalDateTime expiredAt = LocalDateTime.now().plusDays(refreshTokenExpirationDays);

		Optional<RefreshToken> optRefreshToken = refreshTokenRepository.findByUserId(user.getId());

		if(optRefreshToken.isPresent()){
			RefreshToken refreshToken = optRefreshToken.get();
			refreshToken.update(token, expiredAt);
		}
		else {
			try {
				refreshTokenRepository.save(
						RefreshToken.builder()
								.user(user)
								.token(token)
								.expiredAt(expiredAt)
								.build()
				);
			} catch(DataIntegrityViolationException e){
				throw new MyJwtException(ErrorCode.DUPLICATED_CREATE_REFRESH_TOKEN);
			}
		}

		return token;
	}

	public RefreshToken getRefreshToken(String token){
		return refreshTokenRepository.findByToken(token).orElseThrow(() -> new MyJwtException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
	}

	@Transactional
	public void deleteByUserId(Long userId){
		refreshTokenRepository.deleteByUserId(userId);
	}
}
