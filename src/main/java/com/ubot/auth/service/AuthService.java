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
import com.ubot.user.enums.Gender;
import com.ubot.user.enums.UserRole;
import com.ubot.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final PasswordEncoder passwordEncoder;
	private final JwtUtil jwtUtil;
	private final RefreshTokenService refreshTokenService;
	private final UserService userService;
	private static final Pattern EMAIL_PATTERN =
			Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

	@Transactional
	public LoginResponseDto loginUser(LoginRequestDto requestDto){
		if (requestDto == null
				|| !StringUtils.hasText(requestDto.email())
				|| !StringUtils.hasText(requestDto.password())) {
			throw new UserException(ErrorCode.INVALID_LOGIN_REQUEST);
		}

		String email = requestDto.email().trim().toLowerCase(Locale.ROOT);
		User user = userService.getActiveUserByEmail(email).orElse(null);

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
	public LoginResponseDto refreshAccessToken(RefreshTokenRequestDto requestDto){
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
		if(user.getDeletedAt() != null){
			throw new MyJwtException(ErrorCode.DELETED_USER_TOKEN);
		}
		String accessToken = jwtUtil.createAccessToken(user);
		String refreshToken = refreshTokenService.createOrUpdate(user);

		return new LoginResponseDto(accessToken, refreshToken);
	}

	@Transactional
	public void logoutUser(Long userId){
		refreshTokenService.deleteByUserId(userId);
	}

	public void signupUser(SignupRequestDto requestDto) {
		if(requestDto == null) {
			throw new UserException(ErrorCode.INVALID_SIGNUP_REQUEST);
		}

		validateEmail(requestDto.email());
		validatePassword(requestDto.password(), requestDto.passwordConfirm());
		validateName(requestDto.name());
		validateBirthDate(requestDto.birthDate());
		validateGender(requestDto.gender());
		validateResidenceArea(requestDto.residenceArea());

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

		try {
			userService.saveUser(user);
		} catch(DataIntegrityViolationException e) {
			throw new UserException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}
	}

	private void validateEmail(String email) {
		if(!StringUtils.hasText(email)) {
			throw new UserException(ErrorCode.INVALID_EMAIL_FORMAT, "이메일 칸이 비었습니다. 입력해주세요.");
		}

		if(email.length() > 100){
			throw new UserException(ErrorCode.INVALID_EMAIL_FORMAT, "이메일은 최대 100자까지 입력이 가능합니다.");
		}

		if (!EMAIL_PATTERN.matcher(email).matches()) {
			throw new UserException(ErrorCode.INVALID_EMAIL_FORMAT, "이메일 형식을 맞춰서 입력해주세요. ex) user@naver.com");
		}
	}

	private void validatePassword(String password, String passwordConfirm) {
		if (!StringUtils.hasText(password)
				|| !StringUtils.hasText(passwordConfirm)) {
			throw new UserException(ErrorCode.INVALID_PASSWORD_FORMAT, "비밀번호 혹은 비밀번호 확인 칸이 비었습니다. 입력해주세요.");
		}

		int passwordByteLength =
				password.getBytes(StandardCharsets.UTF_8).length;

		if (passwordByteLength < 8 || passwordByteLength > 20) {
			throw new UserException(ErrorCode.INVALID_PASSWORD_FORMAT, "비밀번호가 8자 미만이거나 20자 초과입니다.");
		}

		if (!password.equals(passwordConfirm)) {
			throw new UserException(ErrorCode.PASSWORD_CONFIRM_MISMATCH);
		}
	}
	private void validateName(String name) {
		if(!StringUtils.hasText(name)){
			throw new UserException(ErrorCode.INVALID_NAME_FORMAT, "이름을 입력해주세요.");
		}
		if(name.length() > 20) {
			throw new UserException(ErrorCode.INVALID_NAME_FORMAT, "이름은 20글자 이하로만 입력이 가능합니다.");
		}
	}
	private void validateBirthDate(LocalDate birthDate) {
		if (birthDate == null || birthDate.isAfter(LocalDate.now())) {
			throw new UserException(ErrorCode.INVALID_BIRTHDATE_FORMAT);
		}
	}

	private void validateGender(Gender gender) {
		if(gender == null) {
			throw new UserException(ErrorCode.INVALID_GENDER_FORMAT, "성별을 입력해주세요.");
		}
	}

	private void validateResidenceArea(String residenceArea) {
		if(!StringUtils.hasText(residenceArea)) {
			throw new UserException(ErrorCode.INVALID_RESIDENCE_FORMAT, "사는 지역을 입력해주세요.");
		}

		if(residenceArea.length() > 100){
			throw new UserException(ErrorCode.INVALID_RESIDENCE_FORMAT, "지역 명은 100자를 초과할 수 없습니다.");
		}
	}
}
