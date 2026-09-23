package com.ubot.user.dto.request;

import com.ubot.user.enums.Gender;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UserUpdateRequestDto(
		@Pattern(regexp = ".*\\S.*") @Size(max = 20) String name,
		@PastOrPresent LocalDate birthDate,
		Gender gender,
		@Pattern(regexp = ".*\\S.*") @Size(max = 100) String residenceArea
){
}
