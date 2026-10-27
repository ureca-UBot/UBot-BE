package com.ubot.ranking.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.ranking.dto.model.PopularRankingSnapshot;
import com.ubot.ranking.dto.model.RankingRedisKey;
import com.ubot.ranking.dto.model.TrendRankingSnapshot;
import com.ubot.ranking.enums.RegionSido;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

@DisplayName("랭킹 Redis 저장소 테스트")
class RankingRedisRepositoryTest {
    @Test
    @DisplayName("저장된 값은 스냅샷 타입으로 변환하고 없는 키는 null로 유지한다")
    void convertsStoredValuesBySnapshotTypeAndKeepsMissingKeysNull() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(redisTemplate.opsForValue()).thenReturn(values);

        List<Object> redisValues = new ArrayList<>();
        redisValues.add("popular-value");
        redisValues.add("trend-value");
        for (RegionSido ignored : RegionSido.values()) {
            redisValues.add(null);
        }
        when(values.multiGet(any())).thenReturn(redisValues);

        PopularRankingSnapshot popular = new PopularRankingSnapshot(LocalDateTime.of(2026, 10, 6, 10, 0), List.of());
        TrendRankingSnapshot trend = new TrendRankingSnapshot(LocalDateTime.of(2026, 10, 6, 10, 0), List.of());
        when(objectMapper.convertValue("popular-value", PopularRankingSnapshot.class)).thenReturn(popular);
        when(objectMapper.convertValue("trend-value", TrendRankingSnapshot.class)).thenReturn(trend);

        var snapshots = new RankingRedisRepository(redisTemplate, objectMapper).findSnapshots();

        assertThat(snapshots.get(RankingRedisKey.popular())).isSameAs(popular);
        assertThat(snapshots.get(RankingRedisKey.trend())).isSameAs(trend);
        assertThat(snapshots.get(RankingRedisKey.trend(RegionSido.SEOUL))).isNull();
        verify(objectMapper).convertValue("popular-value", PopularRankingSnapshot.class);
        verify(objectMapper).convertValue("trend-value", TrendRankingSnapshot.class);
    }
}
