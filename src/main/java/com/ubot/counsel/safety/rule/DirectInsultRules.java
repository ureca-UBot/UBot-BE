package com.ubot.counsel.safety.rule;

import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class DirectInsultRules {
	public static final String VERSION = "direct-v1";
	public static final String PREPROCESSING = "nfkc-space-v1";
	// Deliberately narrow, anchored single utterance. Do not use complaint words as a blocklist.
	private static final Pattern DIRECT = Pattern.compile(
			"^(?:당신|너|넌|너는|상담사님|상담사|당신은|상담사님은|상담사는)(?:은|는|이|가)?\\s*"
			+ "(?:(?:정말|진짜|참|너무|완전|아주)\\s*)?"
			+ "(?:무능한\\s*(?:상담사|사람|인간)(?:이)?(?:네요|군요|야|다|예요)|"
			+ "(?:멍청이|바보|등신|쓰레기)(?:네요|군요|야|다|예요|입니다))\\s*[.!?。！？]*$");
	private static final Pattern ADDRESSED = Pattern.compile("^(?:당신|너는|넌|너|상담사님|상담사)(?:은|는|이|가)?\\s+");
	private static final Pattern AMBIGUOUS = Pattern.compile("[\"'“”‘’「」『』]|(?:라고|다고|라는|다는|인용|신고|들었|말했|말하|표현|욕설|욕을|하지\\s*마|아니|않|예시|가정|만약|서비스|요금|청구|절차|안내|설명|환불)");
	private DirectInsultRules() {}
	public static String normalize(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
	}
	public static String contentHash(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalize(text).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}
	public static boolean explicit(String normalized) {
		return DIRECT.matcher(normalized).matches();
	}
	public static boolean semanticEligible(String normalized) {
		// Ambiguous reporting/quotation and process criticism are outside automatic semantic blocking.
		return ADDRESSED.matcher(normalized).find() && !AMBIGUOUS.matcher(normalized).find();
	}
}
