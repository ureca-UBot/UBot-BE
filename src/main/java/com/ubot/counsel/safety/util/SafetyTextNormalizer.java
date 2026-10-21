package com.ubot.counsel.safety.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

public final class SafetyTextNormalizer {
	public static final String PREPROCESSING = "nfkc-space-v1";

	private SafetyTextNormalizer() {}

	public static String normalize(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
	}

	public static String contentHash(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalize(text).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}
}
