package com.ubot.subscription.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 하이픈은 있어도 없어도 된다. */
public record BundleMemberAddRequestDto(
		@NotBlank @Pattern(regexp = "^[0-9-]{9,20}$") String phoneNumber) {
}
