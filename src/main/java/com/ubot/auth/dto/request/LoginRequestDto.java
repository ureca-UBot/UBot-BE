package com.ubot.auth.dto.request;

public record LoginRequestDto (
		String email,
		String password
){
}
