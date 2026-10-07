package com.ubot.ranking.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.ranking.enums.RegionSido;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DisplayName("랭킹 집계 쿼리 통합 테스트")
class RankingQueryRepositoryIntegrationTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 6, 10, 0);
    private static final LocalDateTime END = START.plusHours(1);

    @Autowired private RankingQueryRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    private Long guestConversationId;

    // Scheduling is unrelated to query verification and would otherwise require a Redis server.
    @MockitoBean private com.ubot.ranking.scheduler.RankingScheduler rankingScheduler;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("DELETE FROM faq_log");
        jdbcTemplate.execute("DELETE FROM question_log");
        jdbcTemplate.execute("DELETE FROM faq");
        jdbcTemplate.execute("DELETE FROM faq_category");
        guestConversationId = jdbcTemplate.queryForObject(
                "INSERT INTO conversations (type) VALUES ('GUEST') RETURNING conversation_id", Long.class);
    }

    @Test
    @DisplayName("인기 랭킹은 집계 대상과 시간 범위를 필터링하고 동률을 FAQ ID 순으로 정렬한다")
    void popularRankingExcludesIneligibleAndOutOfWindowLogsAndUsesStableTieBreak() {
        long firstFaq = createFaq("first");
        long secondFaq = createFaq("second");
        addLogs(firstFaq, 3, START.plusMinutes(1), RegionSido.SEOUL, true);
        addLogs(secondFaq, 3, START.plusMinutes(2), RegionSido.BUSAN, true);
        addLogs(secondFaq, 5, START.plusMinutes(3), RegionSido.BUSAN, false);
        addLogs(secondFaq, 5, END, RegionSido.BUSAN, true);

        var rankings = repository.aggregatePopularRanking(START, END, 5, 1);

        assertThat(rankings).extracting(item -> item.faqId())
                .containsExactly(firstFaq, secondFaq);
        assertThat(rankings).extracting(item -> item.rank()).containsExactly(1L, 2L);
        assertThat(rankings).extracting(item -> item.currentCount()).containsExactly(3L, 3L);
    }

    @Test
    @DisplayName("급상승 랭킹은 baseline 대비 비율을 계산하고 요청 지역만 포함한다")
    void trendingRankingUsesBaselineAndFiltersByRegion() {
        long seoulFaq = createFaq("seoul");
        long busanFaq = createFaq("busan");
        addLogs(seoulFaq, 3, START.plusMinutes(1), RegionSido.SEOUL, true);
        addLogs(seoulFaq, 2, START.minusHours(1), RegionSido.SEOUL, true);
        addLogs(busanFaq, 10, START.plusMinutes(2), RegionSido.BUSAN, true);
        addLogs(busanFaq, 2, START.minusHours(1), RegionSido.BUSAN, true);

        var rankings = repository.aggregateTrendingRanking(
                START, END, 60, 5, 1, 1, 1, 1.0d, RegionSido.SEOUL);

        assertThat(rankings).singleElement().satisfies(item -> {
            assertThat(item.faqId()).isEqualTo(seoulFaq);
            assertThat(item.region()).isEqualTo(RegionSido.SEOUL);
            assertThat(item.currentCount()).isEqualTo(3L);
            assertThat(item.baselineTotalCount()).isEqualTo(2L);
            assertThat(item.trendRatio()).isEqualTo(36.0d);
        });
    }

    @Test
    @DisplayName("baseline 표본이 없으면 급상승 랭킹을 비어 있는 목록으로 반환한다")
    void trendingRankingReturnsEmptyWhenTheBaselineSampleIsMissing() {
        long faqId = createFaq("no-baseline");
        addLogs(faqId, 5, START.plusMinutes(1), RegionSido.SEOUL, true);

        List<?> rankings = repository.aggregateTrendingRanking(
                START, END, 60, 5, 1, 1, 1, 1.0d, null);

        assertThat(rankings).isEmpty();
    }

    private long createFaq(String question) {
        Long categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO faq_category (name) VALUES (?) RETURNING id", Long.class, question + "-category");
        return jdbcTemplate.queryForObject(
                "INSERT INTO faq (category_id, question, answer) VALUES (?, ?, ?) RETURNING id",
                Long.class, categoryId, question, "answer");
    }

    private void addLogs(long faqId, int count, LocalDateTime createdAt, RegionSido region, boolean eligible) {
        for (int index = 0; index < count; index++) {
            Long questionLogId = jdbcTemplate.queryForObject("""
                    INSERT INTO question_log (conversation_id, user_question, normalized_question, ranking_eligible, region, created_at)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, guestConversationId,
                    "question-" + faqId + "-" + index + "-" + createdAt,
                    "normalized", eligible, region.name(), createdAt);
            jdbcTemplate.update("INSERT INTO faq_log (question_log_id, faq_id, rank, similarity) VALUES (?, ?, ?, ?)",
                    questionLogId, faqId, 1, 1.0d);
        }
    }
}
