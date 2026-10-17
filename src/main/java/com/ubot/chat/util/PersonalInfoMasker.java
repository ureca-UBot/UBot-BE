package com.ubot.chat.util;

import java.util.regex.Pattern;

public final class PersonalInfoMasker {
	private static final String SEPARATOR = "\\s*[-.]?\\s*";
	private static final Pattern CARD_NUMBER = Pattern.compile(
			"(?<!\\d)\\d{4}(?:" + SEPARATOR + "\\d{4}){2}" + SEPARATOR + "(\\d{4})(?!\\d)");
	private static final Pattern RESIDENT_NUMBER = Pattern.compile(
			"(?<!\\d)(\\d{6})" + SEPARATOR + "[1-8]\\d{6}(?!\\d)");
	private static final Pattern MOBILE_NUMBER = Pattern.compile(
			"(?<!\\d)(?:\\+82" + SEPARATOR + "|0)(1[016789])" + SEPARATOR + "\\d{3,4}" + SEPARATOR
					+ "(\\d{4})(?!\\d)");
	private static final Pattern EMAIL =
			Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

	private PersonalInfoMasker() {
	}

	public static String mask(String text) {
		if (text == null) {
			return null;
		}
		String masked = CARD_NUMBER.matcher(text).replaceAll("****-****-****-$1");
		masked = RESIDENT_NUMBER.matcher(masked).replaceAll("$1-*******");
		masked = MOBILE_NUMBER.matcher(masked).replaceAll("0$1-****-$2");
		return EMAIL.matcher(masked).replaceAll("$1***@$2");
	}
}
