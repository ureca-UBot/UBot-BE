package com.ubot.faq.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.faq.policy.FaqQuestionNormalizer;
import java.text.Normalizer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
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
    void freshSchemaRejectsNormalizedDuplicatesWithoutReactivatingAnExistingExpression() {
        prepareSchema();
        PGvector vector = vector();
        Long canonical = insert("esim이 뭐야?",false,vector);
        Long changed = insert("이심을 설명해줘？？",true,vector);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM faq_cache_question", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT enabled FROM faq_cache_question WHERE id=?",Boolean.class,canonical)).isFalse();
        assertThat(jdbc.queryForObject("SELECT normalization_version FROM faq_cache_question WHERE id=?",Integer.class,canonical)).isZero();
        assertThat(jdbc.queryForObject("SELECT normalization_version FROM faq_cache_question WHERE id=?",Integer.class,changed)).isZero();
        assertThat(jdbc.queryForObject("SELECT question FROM faq_cache_question WHERE id=?",String.class,changed)).isEqualTo("이심을 설명해줘？？");
        assertThat(jdbc.queryForObject("SELECT normalized_question FROM faq_cache_question WHERE id=?",String.class,changed)).isEqualTo("이심을 설명해줘?");
        assertThat(jdbc.queryForObject("SELECT vector = ? FROM faq_cache_question WHERE id=?",Boolean.class,vector,changed)).isTrue();
        int duplicate = jdbc.update("""
                INSERT INTO faq_cache_question(faq_id,profile_id,question,vector,faq_version,faq_question,faq_answer)
                VALUES (14,1,' esim이  뭐야？？？ ',?,1,'공식 질문','공식 답변') ON CONFLICT DO NOTHING
                """,vector);
        assertThat(duplicate).isZero();
        assertThat(jdbc.queryForObject("SELECT enabled FROM faq_cache_question WHERE id=?",Boolean.class,canonical)).isFalse();
    }

    @Test
    void databaseGeneratedKeyMatchesJavaIncludingUnicodeAndLineBoundaries() {
        prepareSchema();
        for (String question : List.of("  eSim이  뭐야？？？  ","esim이\t뭐야!!","esim이\u00A0\u3000뭐야！！",
                Normalizer.normalize("이심이 뭐야?",Normalizer.Form.NFD),"번호이동 없이 번호를 유지해요?",
                "eSIM이 뭐야?? 발급은??", "esim이 뭐야? \n", "esim이 뭐야?\r\n  ","\u00A0\u3000")) {
            jdbc.update("DELETE FROM faq_cache_question");
            Long id = insert(question,true,vector());
            assertThat(jdbc.queryForObject("SELECT normalized_question FROM faq_cache_question WHERE id=?",String.class,id))
                    .as(question).isEqualTo(FaqQuestionNormalizer.normalize(question));
        }
    }

    private void prepareSchema() {
        // Test-owned schema and transaction; all objects are rolled back after each test.
        jdbc.execute("CREATE SCHEMA normalization_initial");
        jdbc.execute("SET LOCAL search_path TO normalization_initial,public");
        jdbc.execute("CREATE TABLE faq(id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO faq VALUES (14)");
        jdbc.execute("CREATE TABLE embedding_profiles(profile_id BIGINT PRIMARY KEY)");
        jdbc.execute("INSERT INTO embedding_profiles VALUES (1)");
        executeMigration("V29__create_faq_cache_questions.sql");
    }

    private void executeMigration(String name) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + name)).populate(connection);
            return null;
        });
    }

    private Long insert(String question, boolean enabled, PGvector vector) {
        return jdbc.queryForObject("""
                INSERT INTO faq_cache_question(faq_id,profile_id,question,vector,faq_version,faq_question,faq_answer,enabled)
                VALUES (14,1,?,?,1,'공식 질문','공식 답변',?) RETURNING id
                """,Long.class,question,vector,enabled);
    }

    private PGvector vector() {
        float[] values = new float[1024];
        values[0] = 1;
        return new PGvector(values);
    }
}
