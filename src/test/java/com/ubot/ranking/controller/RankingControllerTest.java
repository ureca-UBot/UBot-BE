package com.ubot.ranking.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.ranking.dto.model.PopularRankingSnapshot;
import com.ubot.ranking.dto.model.TrendRankingSnapshot;
import com.ubot.ranking.dto.response.RankingResponseDto;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.ranking.service.RankingService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("랭킹 컨트롤러 테스트")
class RankingControllerTest {
    @Test
    @DisplayName("전체 랭킹 조회 API는 인기, 전체 급상승, 지역별 급상승 정보를 반환한다")
    void returnsAllRankingGroupsAtTheRankingsEndpoint() throws Exception {
        RankingService service = mock(RankingService.class);
        LocalDateTime calculatedAt = LocalDateTime.of(2026, 10, 6, 10, 0);
        when(service.getRanking()).thenReturn(new RankingResponseDto(
                new PopularRankingSnapshot(calculatedAt, List.of()),
                null,
                Map.of(RegionSido.SEOUL, new TrendRankingSnapshot(calculatedAt, List.of()))));
        var mvc = MockMvcBuilders.standaloneSetup(new RankingController(service)).build();

        mvc.perform(get("/rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.popular.rankings").isArray())
                .andExpect(jsonPath("$.data.popular.rankings").isEmpty())
                .andExpect(jsonPath("$.data.trend").isEmpty())
                .andExpect(jsonPath("$.data.regionalTrend.SEOUL.rankings").isEmpty());
    }
}
