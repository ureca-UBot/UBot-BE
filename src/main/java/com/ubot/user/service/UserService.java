package com.ubot.user.service;

import com.ubot.common.ErrorCode;
import com.ubot.common.exception.UserException;
import com.ubot.user.dto.request.UserUpdateRequestDto;
import com.ubot.user.dto.response.UserResponseDto;
import com.ubot.user.entity.User;
import com.ubot.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {
	private final UserRepository userRepository;

	public Optional<User> getActiveUserByEmail(String email) {
		return userRepository.findByEmailAndDeletedAtIsNull(email);
	}

	public boolean existsActiveUserByEmail(String email) {
		return userRepository.existsByEmailAndDeletedAtIsNull(email);
	}

	@Transactional
	public void saveUser(User user) {
		userRepository.saveAndFlush(user);
	}

	public UserResponseDto getActiveUser(Long userId) {
		User user = userRepository.findByIdAndDeletedAtIsNull(userId)
				.orElseThrow(() -> new UserException(ErrorCode.USER_NOT_FOUND));
		return UserResponseDto.from(user);
	}

	@Transactional
	public UserResponseDto updateActiveUser(Long userId, UserUpdateRequestDto requestDto) {
		if(requestDto.name() == null
				&& requestDto.birthDate() == null
				&& requestDto.gender() == null
				&& requestDto.residenceArea() == null){
			throw new UserException(ErrorCode.INVALID_USER_UPDATE_REQUEST);
		}

		User user = userRepository.findByIdAndDeletedAtIsNull(userId)
				.orElseThrow(() -> new UserException(ErrorCode.USER_NOT_FOUND));
		user.update(
				requestDto.name(),
				requestDto.birthDate(),
				requestDto.gender(),
				requestDto.residenceArea()
		);
		return UserResponseDto.from(user);
	}
}
