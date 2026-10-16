package com.ubot.chat.util;

import java.util.regex.Pattern;

public final class PersonalInfoMasker {
	private static final Pattern CARD_NUMBER =
			Pattern.compile("(?<!\\d)\\d{4}([-\\s]?)\\d{4}\\1\\d{4}\\1(\\d{4})(?!\\d)");
	private static final Pattern RESIDENT_NUMBER =
			Pattern.compile("(?<!\\d)(\\d{6})[-\\s]?[1-8]\\d{6}(?!\\d)");
	private static final Pattern MOBILE_NUMBER =
			Pattern.compile("(?<!\\d)(01[016789])[-.\\s]?\\d{3,4}[-.\\s]?(\\d{4})(?!\\d)");
	private static final Pattern EMAIL =
			Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

	private PersonalInfoMasker() {
	}

	public static String mask(String text) {
		if (text == null) {
			return null;
		}
		String masked = CARD_NUMBER.matcher(text).replaceAll("****-****-****-$2");
		masked = RESIDENT_NUMBER.matcher(masked).replaceAll("$1-*******");
		masked = MOBILE_NUMBER.matcher(masked).replaceAll("$1-****-$2");
		return EMAIL.matcher(masked).replaceAll("$1***@$2");
	}
}
