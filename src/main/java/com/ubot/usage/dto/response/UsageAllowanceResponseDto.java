package com.ubot.usage.dto.response;

import java.math.BigDecimal;
import java.util.Map;

public record UsageAllowanceResponseDto(
		Long dataAmountMb,
		Boolean dataUnlimited,
		Integer voiceMinutes,
		Boolean voiceUnlimited,
		Integer smsCount,
		Boolean smsUnlimited
) {
	// JSON 스냅샷은 요금제 DTO와 같은 camelCase 키를 사용한다.
	// 불완전한 스냅샷을 현재 요금제 값이나 0으로 대체하지 않는다.
	public static UsageAllowanceResponseDto from(Map<String, Object> snapshot) {
		if (snapshot == null || snapshot.isEmpty()) {
			return null;
		}
		return new UsageAllowanceResponseDto(
				longValue(snapshot.get("dataAmountMb")), booleanValue(snapshot.get("dataUnlimited")),
				intValue(snapshot.get("voiceMinutes")), booleanValue(snapshot.get("voiceUnlimited")),
				intValue(snapshot.get("smsCount")), booleanValue(snapshot.get("smsUnlimited")));
	}

	private static Boolean booleanValue(Object value) {
		return value instanceof Boolean flag ? flag : null;
	}

	private static Long longValue(Object value) {
		if (!(value instanceof Number)) {
			return null;
		}
		try {
			long amount = new BigDecimal(value.toString()).longValueExact();
			return amount >= 0 ? amount : null;
		} catch (ArithmeticException | NumberFormatException exception) {
			return null;
		}
	}

	private static Integer intValue(Object value) {
		Long amount = longValue(value);
		return amount != null && amount <= Integer.MAX_VALUE ? amount.intValue() : null;
	}
}
