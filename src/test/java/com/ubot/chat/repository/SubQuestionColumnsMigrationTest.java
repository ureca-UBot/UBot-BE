package com.ubot.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@DisplayName("하위 질문 컬럼 추가(V35)")
class SubQuestionColumnsMigrationTest {

    @Test
    @DisplayName("기존 행은 하위 질문 1번이 되어 원문을 유지하고, 새 행의 순번은 기본 1·범위 1~3·시도마다 유일하다")
    void migratesExistingRowsAndAddsConstraints() {
        var image = new ImageFromDockerfile("ubot-postgres:18-pgvector0.8.6-postgis3.6.4", false)
                .withFileFromClasspath("Dockerfile", "Dockerfile");
        var imageName = DockerImageName.parse(image.get()).asCompatibleSubstituteFor("postgres");
        try (var postgres = new PostgreSQLContainer(imageName)
                .withDatabaseName("sub_question_columns_migration")
                .withUsername("migration_test")
                .withPassword(UUID.randomUUID().toString())
                .withLabel("com.ubot.test-db", "true")
                .withReuse(false)) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("34").load().migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            Long userId = jdbc.queryForObject("""
                    INSERT INTO users (email, password_hash, name)
                    VALUES ('columns-migration@test.com', 'hash', '컬럼 추가') RETURNING user_id
                    """, Long.class);
            Long groupId = jdbc.queryForObject("""
                    INSERT INTO unanswered_question_groups (representative_question)
                    VALUES ('전환 전 질문') RETURNING id
                    """, Long.class);
            // V35 이전 형식의 질문 로그와 미답변 질문입니다.
            Long legacyLogId = jdbc.queryForObject("""
                    INSERT INTO question_log (user_id, user_question) VALUES (?, '이전 형식') RETURNING id
                    """, Long.class, userId);
            Long legacyAttemptId = attempt(jdbc, userId);
            jdbc.update("""
                    INSERT INTO unanswered_questions (attempt_id, group_id, question, reason)
                    VALUES (?, ?, '이전 미답변', 'NO_FAQ')
                    """, legacyAttemptId, groupId);

            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").load().migrate();

            assertThat(jdbc.queryForMap("""
                    SELECT sub_question_index, sub_question, user_text, attempt_id FROM question_log WHERE id = ?
                    """, legacyLogId))
                    .containsEntry("sub_question_index", 1)
                    .containsEntry("sub_question", null)
                    .containsEntry("user_text", null)
                    .containsEntry("attempt_id", null);
            assertThat(jdbc.queryForMap("""
                    SELECT sub_question_index, sub_question, user_text FROM unanswered_questions WHERE attempt_id = ?
                    """, legacyAttemptId))
                    .containsEntry("sub_question_index", 1)
                    .containsEntry("sub_question", "이전 미답변")
                    .containsEntry("user_text", "이전 미답변");

            Long attemptId = attempt(jdbc, userId);
            assertThat(jdbc.queryForObject("""
                    INSERT INTO question_log (user_id, attempt_id, user_question) VALUES (?, ?, '새 원문')
                    RETURNING sub_question_index
                    """, Integer.class, userId, attemptId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    INSERT INTO unanswered_questions (attempt_id, group_id, sub_question, user_text, reason)
                    VALUES (?, ?, '새 검색어', '새 원문', 'NO_FAQ') RETURNING sub_question_index
                    """, Integer.class, attemptId, groupId)).isEqualTo(1);
            for (int index : List.of(2, 3)) {
                jdbc.update("""
                        INSERT INTO question_log (user_id, attempt_id, user_question, sub_question_index)
                        VALUES (?, ?, '새 원문', ?)
                        """, userId, attemptId, index);
                jdbc.update("""
                        INSERT INTO unanswered_questions (attempt_id, group_id, sub_question, user_text, reason, sub_question_index)
                        VALUES (?, ?, '새 검색어', '새 원문', 'NO_FAQ', ?)
                        """, attemptId, groupId, index);
            }
            Long nextAttemptId = attempt(jdbc, userId);
            for (int invalid : List.of(0, 4)) {
                assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO question_log (user_id, user_question, sub_question_index) VALUES (?, '범위 오류', ?)
                        """, userId, invalid)).isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO unanswered_questions (attempt_id, group_id, sub_question, user_text, reason, sub_question_index)
                        VALUES (?, ?, '범위 오류', '범위 오류', 'NO_FAQ', ?)
                        """, nextAttemptId, groupId, invalid)).isInstanceOf(DataIntegrityViolationException.class);
            }
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO question_log (user_id, attempt_id, user_question, sub_question_index)
                    VALUES (?, ?, '중복', 1)
                    """, userId, attemptId)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO unanswered_questions (attempt_id, group_id, sub_question, user_text, reason, sub_question_index)
                    VALUES (?, ?, '중복', '중복', 'NO_FAQ', 1)
                    """, attemptId, groupId)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private Long attempt(JdbcTemplate jdbc, Long userId) {
        return jdbc.queryForObject("""
                INSERT INTO answer_attempts_history (user_id, question, attempt_count, status, idempotency_key)
                VALUES (?, '원문 전체', 1, 'SUCCESS', ?) RETURNING attempt_id
                """, Long.class, userId, UUID.randomUUID().toString());
    }
}
