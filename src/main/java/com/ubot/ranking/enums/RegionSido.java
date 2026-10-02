package com.ubot.ranking.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum RegionSido {
	SEOUL("11"),
	BUSAN("26"),
	DAEGU("27"),
	INCHEON("28"),
	GWANGJU("29"),
	DAEJEON("30"),
	ULSAN("31"),
	SEJONG("50"),

	GYEONGGI("41"),
	GANGWON("42"),
	CHUNGBUK("43"),
	CHUNGNAM("44"),
	JEONBUK("45"),
	JEONNAM("46"),
	GYEONGBUK("47"),
	GYEONGNAM("48"),
	JEJU("49");

	private final String code;

	public static Optional<RegionSido> fromCode(String code) {
		if (code == null) {
			return Optional.empty();
		}

		return Arrays.stream(values())
				.filter(regionSido -> regionSido.code.equals(code))
				.findFirst();
	}
}
