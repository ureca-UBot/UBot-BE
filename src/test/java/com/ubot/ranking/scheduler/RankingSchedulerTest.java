package com.ubot.ranking.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ubot.ranking.service.RankingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("랭킹 스케줄러 테스트")
class RankingSchedulerTest {
    @Test
    @DisplayName("스케줄 실행 시 랭킹 서비스의 집계를 호출한다")
    void delegatesRefreshToRankingService() {
        RankingService rankingService = mock(RankingService.class);

        new RankingScheduler(rankingService).refreshRanking();

        verify(rankingService).refreshRanking();
    }
}
