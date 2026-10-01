package com.ubot.ranking.repository;

import com.ubot.ranking.dto.model.RankingSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
@RequiredArgsConstructor
public class RankingRedisRepository {
	private final RedisTemplate<String, Object> redisTemplate;

	public void saveSnapshots(Map<String, RankingSnapshot> snapshots) {
		redisTemplate.opsForValue().multiSet(snapshots);
	}
}
