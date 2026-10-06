package com.ubot.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.ranking.dto.model.PopularRankingItem;
import com.ubot.ranking.dto.model.PopularRankingSnapshot;
import com.ubot.ranking.dto.model.RankingRedisKey;
import com.ubot.ranking.dto.model.RankingSnapshot;
import com.ubot.ranking.dto.model.TrendRankingItem;
import com.ubot.ranking.dto.model.TrendRankingSnapshot;
import com.ubot.ranking.entity.RegionalTrendRankingPolicy;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.ranking.repository.RankingQueryRepository;
import com.ubot.ranking.repository.RankingRedisRepository;
import com.ubot.ranking.repository.RegionalTrendRankingPolicyRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("랭킹 서비스 테스트")
class RankingServiceTest {
    private final RankingQueryRepository queryRepository = mock(RankingQueryRepository.class);
    private final RankingRedisRepository redisRepository = mock(RankingRedisRepository.class);
    private final RegionalTrendRankingPolicyRepository policyRepository = mock(RegionalTrendRankingPolicyRepository.class);
    private final RankingService service = new RankingService(queryRepository, redisRepository, policyRepository);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "rankingWindowMinutes", 60);
        ReflectionTestUtils.setField(service, "rankingSize", 5);
        ReflectionTestUtils.setField(service, "minCurrentSearchCount", 20);
        ReflectionTestUtils.setField(service, "baselinePeriodDays", 7);
        ReflectionTestUtils.setField(service, "baselineMinSampleCount", 20);
        ReflectionTestUtils.setField(service, "trendRatioThreshold", 2.0d);
    }

    @Test
    @DisplayName("집계 결과를 인기, 전체 급상승, 모든 지역 급상승 스냅샷으로 저장한다")
    void refreshStoresPopularGlobalAndEveryRegionalSnapshot() {
        when(queryRepository.aggregatePopularRanking(any(), any(), eq(5), eq(20)))
                .thenReturn(List.of(new PopularRankingItem(1L, 1L, "popular", "answer", 20L)));
        when(queryRepository.aggregateTrendingRanking(any(), any(), eq(60), eq(5), anyInt(), eq(7), anyInt(), anyDouble(), any()))
                .thenReturn(List.of());
        when(policyRepository.findAll()).thenReturn(List.of());

        service.refreshRanking();

        ArgumentCaptor<Map<String, RankingSnapshot>> snapshots = ArgumentCaptor.forClass(Map.class);
        verify(redisRepository).saveSnapshots(snapshots.capture());
        Map<String, RankingSnapshot> saved = snapshots.getValue();
        assertThat(saved).hasSize(2 + RegionSido.values().length);
        assertThat(saved.get(RankingRedisKey.popular())).isInstanceOf(PopularRankingSnapshot.class);
        assertThat(saved.get(RankingRedisKey.trend())).isInstanceOf(TrendRankingSnapshot.class);
        for (RegionSido region : RegionSido.values()) {
            assertThat(saved.get(RankingRedisKey.trend(region))).isInstanceOf(TrendRankingSnapshot.class);
        }
    }

    @Test
    @DisplayName("지역별 정책이 있으면 전역 기본값 대신 지역 정책 임계값을 사용한다")
    void refreshUsesRegionalPolicyInsteadOfGlobalThresholds() {
        RegionalTrendRankingPolicy seoulPolicy = RegionalTrendRankingPolicy.builder()
                .regionName(RegionSido.SEOUL).minCurrentSearchCount(3)
                .minBaselineSampleCount(4).trendRatioThreshold(1.5d).build();
        when(policyRepository.findAll()).thenReturn(List.of(seoulPolicy));
        when(queryRepository.aggregatePopularRanking(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(queryRepository.aggregateTrendingRanking(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyDouble(), any()))
                .thenReturn(List.of());

        service.refreshRanking();

        verify(queryRepository).aggregateTrendingRanking(any(), any(), eq(60), eq(5), eq(3), eq(7), eq(4), eq(1.5d), eq(RegionSido.SEOUL));
        verify(queryRepository).aggregateTrendingRanking(any(), any(), eq(60), eq(5), eq(20), eq(7), eq(20), eq(2.0d), eq(RegionSido.BUSAN));
    }

    @Test
    @DisplayName("없는 Redis 스냅샷은 null로, 집계된 빈 순위는 빈 목록으로 응답한다")
    void getRankingKeepsMissingSnapshotsAsNullAndKeepsAnEmptyComputedList() {
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 10, 6, 10, 0);
        PopularRankingSnapshot popular = new PopularRankingSnapshot(calculatedAt, List.of());
        TrendRankingSnapshot seoul = new TrendRankingSnapshot(calculatedAt,
                List.of(new TrendRankingItem(1L, 2L, "trend", "answer", 20L, 30L, 1.0d, 20.0d, RegionSido.SEOUL)));
        when(redisRepository.findSnapshots()).thenReturn(Map.of(
                RankingRedisKey.popular(), popular,
                RankingRedisKey.trend(RegionSido.SEOUL), seoul));

        var response = service.getRanking();

        assertThat(response.popular()).isSameAs(popular);
        assertThat(response.popular().rankings()).isEmpty();
        assertThat(response.trend()).isNull();
        assertThat(response.regionalTrend().get(RegionSido.SEOUL)).isSameAs(seoul);
        assertThat(response.regionalTrend().get(RegionSido.BUSAN)).isNull();
    }
}
