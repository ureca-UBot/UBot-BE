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
@DisplayName("FAQ 벡터 저장소 통합 테스트")
class FaqVectorRepositoryTest {
    @Autowired private FaqVectorRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM old_faq");
        jdbcTemplate.update("DELETE FROM faq");
        jdbcTemplate.update("DELETE FROM faq_category");
        categoryId = jdbcTemplate.queryForObject("INSERT INTO faq_category (name) VALUES ('벡터 테스트') RETURNING id", Long.class);
        insertFaq(1L, "유심 재발급", "매장에서 재발급할 수 있습니다.", vectorOf(0), null);
        insertFaq(2L, "유심 인식 오류", "휴대전화를 다시 시작해 주세요.", vectorOf(1), null);
        insertFaq(3L, "삭제된 FAQ", "검색되면 안 됩니다.", vectorOf(0), "2026-01-01 00:00:00");
    }

    @Test
    @DisplayName("유사도 검색은 가장 가까운 활성 FAQ부터 반환하고 삭제 FAQ는 제외한다")
    void getSimilarList_returnsNearestActiveFaqOnly() {
        List<FaqSearchResponseDto> results = repository.getSimilarList(vectorOf(0), 10);

        assertThat(results).extracting(FaqSearchResponseDto::faqId).containsExactly(1L, 2L);
        assertThat(results.getFirst().similarityScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("유사도 검색은 요청한 상위 결과 수를 초과하지 않는다")
    void getSimilarList_limitsResultsToTopK() {
        assertThat(repository.getSimilarList(vectorOf(0), 1)).hasSize(1);
    }

    @Test
    @DisplayName("현재 FAQ 벡터를 조회하고 이전 FAQ 이력에 저장한다")
    void findAndSaveVector_handlesCurrentAndHistoryFaq() {
        jdbcTemplate.update("INSERT INTO old_faq (faq_id, version, category_id, question, answer, updated_at) VALUES (1, 1, ?, '이전 질문', '이전 답변', now())", categoryId);

        PGvector vector = repository.findVectorByFaqId(1L);
        repository.saveVectorForOldFaq(1L, 1, vector);

        PGvector saved = jdbcTemplate.queryForObject(
                "SELECT vector FROM old_faq WHERE faq_id = 1 AND version = 1",
                (rs, rowNum) -> new PGvector(rs.getString("vector"))
        );
        assertThat(saved.getValue()).startsWith("[1.0,0.0");
    }

    private void insertFaq(Long id, String question, String answer, PGvector vector, String deletedAt) {
        jdbcTemplate.update("INSERT INTO faq (id, category_id, question, answer, vector, deleted_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?::timestamp, now(), now())", id, categoryId, question, answer, vector, deletedAt);
    }

    private PGvector vectorOf(int index) { float[] values = new float[1024]; values[index] = 1.0f; return new PGvector(values); }
}
