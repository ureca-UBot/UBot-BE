package com.ubot.user.dto.response;

import com.ubot.user.entity.User;
import com.ubot.user.enums.Gender;
import com.ubot.user.enums.UserRole;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record UserResponseDto(
		Long id,
		String email,
		String name,
		LocalDate birthDate,
		Gender gender,
		String residenceArea,
		UserRole role,
		LocalDateTime createdAt
) {
	public static UserResponseDto from(User user) {
		return new UserResponseDto(
				user.getId(),
				user.getEmail(),
				user.getName(),
				user.getBirthDate(),
				user.getGender(),
				user.getResidenceArea(),
				user.getRole(),
				user.getCreatedAt()
		);
	}
}
