package com.ubot.faq.repository;

import com.ubot.faq.dto.response.FaqSearchResponseDto;

import lombok.RequiredArgsConstructor;

import com.pgvector.PGvector;
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
}