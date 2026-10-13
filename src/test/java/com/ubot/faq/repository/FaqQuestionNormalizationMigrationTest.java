package com.ubot.faq.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.faq.policy.FaqQuestionNormalizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@Transactional
class FaqQuestionNormalizationMigrationTest {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void freshSchemaRequiresAJavaSuppliedKeyRatherThanADatabaseExpression() {
        prepareSchema();
        assertThat(jdbc.queryForObject("""
                SELECT is_generated FROM information_schema.columns
                WHERE table_schema = 'normalization_initial' AND table_name = 'faq_cache_question'
                  AND column_name = 'normalized_question'
                """, String.class)).isEqualTo("NEVER");
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'normalization_initial' AND table_name = 'faq_cache_question'
                  AND column_name = 'normalized_question'
                """, String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("""
                SELECT identity_generation FROM information_schema.columns
                WHERE table_schema = 'normalization_initial' AND table_name = 'faq_cache_question' AND column_name = 'id'
                """, String.class)).isEqualTo("BY DEFAULT");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conrelid='normalization_initial.faq_cache_question'::regclass
                  AND conname='uq_faq_cache_question_normalized' AND contype='u'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname='normalization_initial'
                  AND tablename='faq_cache_question' AND indexname='idx_faq_cache_question_profile_faq'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void javaSuppliedKeysKeepTheDatabaseUniqueConstraintAndOriginalQuestion() {
        prepareSchema();
        String original = "  esim이  뭐야？？？ ";
        String normalized = FaqQuestionNormalizer.normalize(original);
        Long id = jdbc.queryForObject("""
                INSERT INTO faq_cache_question(faq_id,profile_id,question,normalized_question,vector,
                    faq_version,faq_question,faq_answer,enabled)
                VALUES (14,1,?,?,?,1,'공식 질문','공식 답변',false) RETURNING id
                """, Long.class, original, normalized, vector());
        assertThat(jdbc.queryForObject("SELECT question FROM faq_cache_question WHERE id=?", String.class, id)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT normalized_question FROM faq_cache_question WHERE id=?", String.class, id))
                .isEqualTo(normalized);
        // Direct imports remain untrusted until a normalized embedding is explicitly registered.
        assertThat(jdbc.queryForObject("SELECT normalization_version FROM faq_cache_question WHERE id=?", Integer.class, id)).isZero();
        String duplicate = "esim이\t뭐야??";
        int inserted = jdbc.update("""
                INSERT INTO faq_cache_question(faq_id,profile_id,question,normalized_question,vector,
                    faq_version,faq_question,faq_answer)
                VALUES (14,1,?,?,?,1,'공식 질문','공식 답변') ON CONFLICT DO NOTHING
                """, duplicate, FaqQuestionNormalizer.normalize(duplicate), vector());
        assertThat(inserted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM faq_cache_question", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT enabled FROM faq_cache_question WHERE id=?", Boolean.class, id)).isFalse();
    }

    @Test
    void missingNormalizedKeyIsRejectedInsteadOfSilentlyGenerated() {
        prepareSchema();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO faq_cache_question(faq_id,profile_id,question,vector,faq_version,faq_question,faq_answer)
                VALUES (14,1,'esim이 뭐야?',?,1,'공식 질문','공식 답변')
                """, vector())).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void prepareSchema() {
        // Test-owned schema and transaction; all objects are rolled back after each test.
        jdbc.execute("CREATE SCHEMA normalization_initial");
        jdbc.execute("SET LOCAL search_path TO normalization_initial,public");
        jdbc.execute("CREATE TABLE faq(id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO faq VALUES (14)");
        jdbc.execute("CREATE TABLE embedding_profiles(profile_id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO embedding_profiles VALUES (1)");
        executeMigration("V30__create_faq_cache_questions.sql");
    }

    private void executeMigration(String name) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + name)).populate(connection);
            return null;
        });
    }

    private PGvector vector() {
        float[] values = new float[1024];
        values[0] = 1;
        return new PGvector(values);
    }
}
