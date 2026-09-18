package com.ubot.auth.service;

import com.ubot.auth.dto.request.LoginRequestDto;
import com.ubot.auth.dto.request.RefreshTokenRequestDto;
import com.ubot.auth.dto.request.SignupRequestDto;
import com.ubot.auth.dto.response.LoginResponseDto;
import com.ubot.auth.entity.RefreshToken;
import com.ubot.auth.util.JwtUtil;
import com.ubot.common.ErrorCode;
import com.ubot.common.exception.MyJwtException;
import com.ubot.common.exception.UserException;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final PasswordEncoder passwordEncoder;
	private final JwtUtil jwtUtil;
	private final RefreshTokenService refreshTokenService;
	private final UserService userService;

	@Transactional
	public LoginResponseDto login(LoginRequestDto requestDto){
		if (requestDto == null
				|| !StringUtils.hasText(requestDto.email())
				|| !StringUtils.hasText(requestDto.password())) {
			throw new UserException(ErrorCode.INVALID_LOGIN_REQUEST);
		}

		String email = requestDto.email().trim().toLowerCase(Locale.ROOT);
		User user = userService.findActiveUserByEmail(email).orElse(null);

		if(user == null
				|| !passwordEncoder.matches(requestDto.password(), user.getHashedPassword())
		){
			throw new UserException(ErrorCode.LOGIN_FAILED);
		}

		String accessToken = jwtUtil.createAccessToken(user);
		String refreshToken = refreshTokenService.createOrUpdate(user);

		return new LoginResponseDto(accessToken, refreshToken);
	}

	@Transactional
	public LoginResponseDto refresh(RefreshTokenRequestDto requestDto){
		if(requestDto == null
				|| !StringUtils.hasText(requestDto.refreshToken())
		){
			throw new MyJwtException(ErrorCode.INVALID_REFRESH_TOKEN_REQUEST);
		}

		RefreshToken storedRefreshToken = refreshTokenService.getRefreshToken(requestDto.refreshToken());

		if(!storedRefreshToken.getExpiredAt().isAfter(LocalDateTime.now())){
			throw new MyJwtException(ErrorCode.REFRESH_TOKEN_EXPIRED);
		}

		User user = storedRefreshToken.getUser();
		String accessToken = jwtUtil.createAccessToken(user);
		String refreshToken = refreshTokenService.createOrUpdate(user);

		return new LoginResponseDto(accessToken, refreshToken);
	}

	@Transactional
	public void logout(Long userId){
		refreshTokenService.deleteByUserId(userId);
	}

	@Transactional
	public void signup(SignupRequestDto requestDto) {
		if(requestDto == null
				|| !StringUtils.hasText(requestDto.email())
				|| !StringUtils.hasText(requestDto.password())
				|| !StringUtils.hasText(requestDto.passwordConfirm())
				|| !StringUtils.hasText(requestDto.name())
				|| requestDto.birthDate() == null
				|| requestDto.gender() == null
				|| !StringUtils.hasText(requestDto.residenceArea())) {
			throw new UserException(ErrorCode.INVALID_SIGNUP_REQUEST);
		}

		if(!requestDto.password().equals(requestDto.passwordConfirm())) {
			throw new UserException(ErrorCode.PASSWORD_CONFIRM_MISMATCH);
		}
		// Password 길이 제한은 일단은 안거는걸로(테스트 편의성 위해서)

		String email = requestDto.email().trim().toLowerCase(Locale.ROOT);
		if(userService.existsActiveUserByEmail(email)) {
			throw new UserException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}

		LocalDateTime now = LocalDateTime.now();
		User user = User.builder()
				.email(email)
				.hashedPassword(passwordEncoder.encode(requestDto.password()))
				.name(requestDto.name().trim())
				.birthDate(requestDto.birthDate())
				.gender(requestDto.gender())
				.residenceArea(requestDto.residenceArea())
				.role(UserRole.USER)
				.createdAt(now)
				.updatedAt(now)
				.build();

		userService.save(user);
	}
}
