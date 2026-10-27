package com.ubot.auth.dto.response;

public record LoginResponseDto (
		String accessToken,
		String refreshToken
){
}
