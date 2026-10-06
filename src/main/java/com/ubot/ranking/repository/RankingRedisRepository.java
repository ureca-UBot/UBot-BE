package com.ubot.ranking.repository;

import com.ubot.ranking.dto.model.PopularRankingSnapshot;
import com.ubot.ranking.dto.model.RankingRedisKey;
import com.ubot.ranking.dto.model.RankingSnapshot;
import com.ubot.ranking.dto.model.TrendRankingSnapshot;
import com.ubot.ranking.enums.RegionSido;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Repository
@RequiredArgsConstructor
public class RankingRedisRepository {
	private final RedisTemplate<String, Object> redisTemplate;
	private final ObjectMapper objectMapper;

	public void saveSnapshots(Map<String, RankingSnapshot> snapshots) {
		redisTemplate.opsForValue().multiSet(snapshots);
	}

	public Map<String, RankingSnapshot> findSnapshots() {

		List<String> keys = new ArrayList<>();

		keys.add(RankingRedisKey.popular());
		keys.add(RankingRedisKey.trend());

		for (RegionSido region : RegionSido.values()) {
			keys.add(RankingRedisKey.trend(region));
		}

		List<Object> values = redisTemplate.opsForValue().multiGet(keys);

		if (values == null) {
			return Map.of();
		}

		Map<String, Object> snapshots = new HashMap<>();
		Map<String, RankingSnapshot> rankingSnapshots = new HashMap<>();

		for (int i = 0; i < keys.size(); i++) {
			snapshots.put(keys.get(i), values.get(i));
		}
		rankingSnapshots.put(RankingRedisKey.popular(), convertSnapshot(
				snapshots,
				RankingRedisKey.popular(),
				PopularRankingSnapshot.class
		));

		rankingSnapshots.put(RankingRedisKey.trend(), convertSnapshot(
				snapshots,
				RankingRedisKey.trend(),
				TrendRankingSnapshot.class
		));

		for (RegionSido region : RegionSido.values()) {
			rankingSnapshots.put(RankingRedisKey.trend(region), convertSnapshot(
					snapshots,
					RankingRedisKey.trend(region),
					TrendRankingSnapshot.class
			));
		}
		return rankingSnapshots;
	}

	public <T> T convertSnapshot(
			Map<String, Object> snapshots,
			String key,
			Class<T> type
	){
		Object value = snapshots.get(key);

		if(value == null)
			return null;

		return objectMapper.convertValue(value, type);
	}
}
