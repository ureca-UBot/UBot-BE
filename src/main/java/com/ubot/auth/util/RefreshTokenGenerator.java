package com.ubot.auth.util;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class RefreshTokenGenerator {
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	public String generateRefreshToken() {
		byte[] bytes = new byte[32];
		SECURE_RANDOM.nextBytes(bytes);

		return Base64.getEncoder().withoutPadding().encodeToString(bytes);
	}

	public String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
