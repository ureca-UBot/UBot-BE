package com.ubot.faq.repository;

import com.ubot.faq.dto.response.FaqSearchResponseDto;

import lombok.RequiredArgsConstructor;

import com.pgvector.PGvector;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class FaqVectorRepository {
        private final JdbcTemplate jdbcTemplate;

        public List<FaqSearchResponseDto> getSimilarList(PGvector queryEmbedding, int topK) {
                String sql = """
                                SELECT id, question, answer, 1 - (vector <=> ?) AS similarity_score
                                FROM faq
                                WHERE deleted_at IS NULL
                                ORDER BY vector <=> ?
                                LIMIT ?
                                """;

                return jdbcTemplate.query(sql,
                                (rs, rowNum) -> new FaqSearchResponseDto(
                                                rs.getLong("id"),
                                                rs.getString("question"),
                                                rs.getString("answer"),
                                                rs.getDouble("similarity_score")),
                                queryEmbedding, queryEmbedding, topK);
        }

        public void saveVectorForFaq(Long faqId, PGvector embedding) {
                jdbcTemplate.update("UPDATE faq SET vector = ? WHERE id = ?", embedding, faqId);
        }

        public PGvector findVectorByFaqId(Long faqId) {
                return jdbcTemplate.queryForObject(
                        """
						SELECT vector
						FROM faq
						WHERE id = ?
						""",
                        (rs, rowNum) -> {
							PGobject pgObject = (PGobject) rs.getObject("vector");
							if(pgObject == null) {
								return null;
							}
							return new PGvector(pgObject.getValue());
						},
						faqId
                );
        }

        public void saveVectorForOldFaq(Long faqId, Integer version, PGvector vector) {
                jdbcTemplate.update("UPDATE old_faq SET vector = ? WHERE faq_id = ? and version = ?", vector, faqId, version);
        }
}