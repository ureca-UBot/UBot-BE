package com.ubot.ranking.dto.model;

import com.ubot.ranking.enums.RegionSido;

public final class RankingRedisKey {
	private static final String PREFIX = "ranking:";

	public static String popular() {
		return PREFIX + "popular";
	}

	public static String trending() {
		return PREFIX + "trending:global";
	}
	public static String trending(RegionSido region) {
		return PREFIX + "trending:" + region.toString().toLowerCase();
	}

	private RankingRedisKey() {
	}
}
