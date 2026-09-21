package com.ubot.faq.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
class FaqVectorRepositoryTest {

    @Autowired
    private FaqVectorRepository faqVectorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long categoryId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM faq");
        jdbcTemplate.update("DELETE FROM faq_category");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO faq_category (name) VALUES ('test-category') RETURNING id",
                Long.class
        );
        insertFaq(1L, "USIM reissue", "Visit a nearby store.", vectorOf(0), null);
        insertFaq(2L, "USIM recognition error", "Restart the device.", vectorOf(1), null);
        insertFaq(3L, "deleted FAQ", "This must not be returned.", vectorOf(0), "2026-01-01 00:00:00");
    }

    @Test
    @DisplayName("유사도 검색은 가장 가까운 활성 FAQ를 먼저 반환한다")
    void getSimilarList_returnsNearestActiveFaqFirst() {
        // given
        PGvector queryVector = vectorOf(0);

        // when
        List<FaqSearchResponseDto> results = faqVectorRepository.getSimilarList(queryVector, 10);

        // then
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.getFirst().question()).isEqualTo("USIM reissue");
    }

    @Test
    @DisplayName("유사도 검색은 삭제된 FAQ를 결과에서 제외한다")
    void getSimilarList_excludesDeletedFaq() {
        // given
        PGvector queryVector = vectorOf(0);

        // when
        List<FaqSearchResponseDto> results = faqVectorRepository.getSimilarList(queryVector, 10);

        // then
        assertThat(results).noneMatch(result -> result.question().equals("deleted FAQ"));
    }

    @Test
    @DisplayName("유사도 검색은 요청한 개수 이하의 결과를 반환한다")
    void getSimilarList_returnsAtMostTopKResults() {
        // given
        PGvector queryVector = vectorOf(0);

        // when
        List<FaqSearchResponseDto> results = faqVectorRepository.getSimilarList(queryVector, 1);

        // then
        assertThat(results).hasSize(1);
    }

    private void insertFaq(Long id, String question, String answer, PGvector vector, String deletedAt) {
        jdbcTemplate.update(
                "INSERT INTO faq (id, category_id, question, answer, vector, deleted_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::timestamp, now(), now())",
                id, categoryId, question, answer, vector, deletedAt
        );
    }

    private PGvector vectorOf(int highDimensionIndex) {
        float[] values = new float[1024];
        values[highDimensionIndex] = 1.0f;
        return new PGvector(values);
    }
}
