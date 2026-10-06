package com.ubot.ranking.dto.model;

import com.ubot.ranking.enums.RegionSido;

public final class RankingRedisKey {
	private static final String PREFIX = "ranking:";

	public static String popular() {
		return PREFIX + "popular";
	}

	public static String trend() {
		return PREFIX + "trend:global";
	}
	public static String trend(RegionSido region) {
		return PREFIX + "trend:" + region.toString().toLowerCase();
	}

	private RankingRedisKey() {
	}
}
