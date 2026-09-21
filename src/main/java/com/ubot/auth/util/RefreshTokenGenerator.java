package com.ubot.auth.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class RefreshTokenGenerator {
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	public String generateRefreshToken() {
		byte[] bytes = new byte[32];
		SECURE_RANDOM.nextBytes(bytes);

		return Base64.getEncoder().withoutPadding().encodeToString(bytes);
	}
}
