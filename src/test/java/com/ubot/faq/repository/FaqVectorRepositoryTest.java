package com.ubot.faq.repository;

import com.ubot.faq.dto.FaqSearchResponseDto;
import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        // faq가 faq_category를 참조하므로, faq부터 지우고 category를 지워야 FK 위반이 없음
        jdbcTemplate.update("DELETE FROM faq");
        jdbcTemplate.update("DELETE FROM faq_category");

        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO faq_category (name) VALUES ('테스트 카테고리') RETURNING id",
                Long.class);

        insertFaq(1L, "유심 재발급 방법", "가까운 매장에서 가능합니다", vectorOf(0), null);
        insertFaq(2L, "유심 인식 오류", "재부팅해보세요", vectorOf(1), null);
        insertFaq(3L, "삭제된 FAQ", "이건 안 보여야 함", vectorOf(0), "2026-01-01 00:00:00");
    }

    private void insertFaq(Long id, String question, String answer, PGvector vector, String deletedAt) {
        jdbcTemplate.update(
                "INSERT INTO faq (id, category_id, question, answer, vector, deleted_at, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::timestamp, now(), now())",
                id, categoryId, question, answer, vector, deletedAt);
    }

    /** 1024차원 중 지정한 인덱스만 1.0, 나머지는 0인 벡터. 인덱스가 다르면 서로 완전히 다른(직교) 벡터가 된다. */
    private PGvector vectorOf(int highDimensionIndex) {
        float[] values = new float[1024];
        values[highDimensionIndex] = 1.0f;
        return new PGvector(values);
    }

    @Test
    void 저장된_벡터와_가까운_순서로_검색한다() {
        PGvector queryVector = vectorOf(0);

        List<FaqSearchResponseDto> results = faqVectorRepository.getSimilarList(queryVector, 10);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).question()).isEqualTo("유심 재발급 방법");
    }

    @Test
    void 삭제된_FAQ는_검색_결과에서_제외된다() {
        PGvector queryVector = vectorOf(0);

        List<FaqSearchResponseDto> results = faqVectorRepository.getSimilarList(queryVector, 10);

        assertThat(results).noneMatch(r -> r.question().equals("삭제된 FAQ"));
    }

    @Test
    void topK만큼만_결과를_반환한다() {
        PGvector queryVector = vectorOf(0);

        List<FaqSearchResponseDto> results = faqVectorRepository.getSimilarList(queryVector, 1);

        assertThat(results).hasSize(1);
    }

    @Test
    void saveEmbedding으로_벡터를_갱신할_수_있다() {
        PGvector newVector = vectorOf(2);

        faqVectorRepository.saveEmbedding(1L, newVector);

        List<FaqSearchResponseDto> results = faqVectorRepository.getSimilarList(newVector, 10);
        assertThat(results.get(0).question()).isEqualTo("유심 재발급 방법");
    }
}