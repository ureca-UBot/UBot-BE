package com.ubot;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UbotBeApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @Autowired
    private VectorStore vectorStore;

    @Test
    void contextUsesOnlyTheDisposableTestDatabase() throws SQLException {
        assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("test");
        assertThat(postgresContainer.isRunning()).isTrue();
        assertThat(postgresContainer.isShouldBeReused()).isFalse();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgresContainer.getJdbcUrl());
            assertThat(connection.getCatalog()).isEqualTo("ubot_test");
        }
        assertThat(context.getBeansOfType(OllamaChatModel.class)).isEmpty();
        assertThat(context.getBeansOfType(OllamaEmbeddingModel.class)).isEmpty();
    }

    @Test
    void flywayEnablesRequiredDatabaseExtensions() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT version
                FROM flyway_schema_history
                WHERE success = true
                  AND version IN ('1', '2')
                  AND type = 'SQL'
                ORDER BY installed_rank
                """, String.class)).containsExactly("1", "2");

        assertThat(jdbcTemplate.queryForList("""
                SELECT extname || ':' || extversion
                FROM pg_extension
                WHERE extname IN ('vector', 'postgis')
                ORDER BY extname
                """, String.class)).containsExactly("postgis:3.6.4", "vector:0.8.6");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT ST_SRID(ST_SetSRID(ST_MakePoint(
                    127.0::double precision,
                    37.5::double precision
                ), 4326))
                """, Integer.class)).isEqualTo(4326);
    }

    @Test
    void pgvectorUsesTheConfiguredSchema() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT format_type(atttypid, atttypmod)
                FROM pg_attribute
                WHERE attrelid = 'public.vector_store'::regclass
                  AND attname = 'embedding'
                """, String.class)).isEqualTo("vector(1024)");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'vector_store'
                  AND indexdef LIKE '%USING hnsw%' AND indexdef LIKE '%vector_cosine_ops%'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void pgvectorStoresAndSearchesWithoutOllama() {
        Document document = new Document("UBot pgvector integration test");
        try {
            vectorStore.add(List.of(document));
            var matches = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(document.getText())
                    .topK(1)
                    .similarityThreshold(0.99)
                    .build());
            assertThat(matches).extracting(Document::getId).containsExactly(document.getId());
        }
        finally {
            vectorStore.delete(List.of(document.getId()));
        }
    }

}
