package com.ubot.auth.dto.request;

import com.ubot.user.enums.Gender;

import java.time.LocalDate;

public record SignupRequestDto (
		String email,
		String password,
		String passwordConfirm,
		String name,
		LocalDate birthDate,
		Gender gender,
		String residenceArea
){
}
