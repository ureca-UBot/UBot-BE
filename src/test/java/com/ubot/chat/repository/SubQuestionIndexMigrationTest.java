package com.ubot.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
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

@DisplayName("하위 질문 순번의 V34 → V35 데이터 전환")
class SubQuestionIndexMigrationTest {

    @Test
    @DisplayName("동일 시도의 0·1·2번을 충돌 없이 1·2·3번으로 옮기고 기존 연결·기본값·범위·유일성을 보존한다")
    void migratesExistingIndexesAndPreservesConstraints() {
        var image = new ImageFromDockerfile("ubot-postgres:18-pgvector0.8.6-postgis3.6.4", false)
                .withFileFromClasspath("Dockerfile", "Dockerfile");
        var imageName = DockerImageName.parse(image.get()).asCompatibleSubstituteFor("postgres");
        try (var postgres = new PostgreSQLContainer(imageName)
                .withDatabaseName("sub_question_index_migration")
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
                    VALUES ('index-migration@test.com', 'hash', '순번 전환') RETURNING user_id
                    """, Long.class);
            Long attemptId = attempt(jdbc, userId);
            Long groupId = jdbc.queryForObject("""
                    INSERT INTO unanswered_question_groups (representative_question)
                    VALUES ('전환 전 질문') RETURNING id
                    """, Long.class);
            List<Long> logIds = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                logIds.add(jdbc.queryForObject("""
                        INSERT INTO question_log (user_id, attempt_id, user_question, sub_question,
                                                  user_text, sub_question_index)
                        VALUES (?, ?, '원문 전체', ?, ?, ?) RETURNING id
                        """, Long.class, userId, attemptId, "검색어 " + index, "원문 조각 " + index, index));
                jdbc.update("""
                        INSERT INTO unanswered_questions (attempt_id, group_id, sub_question, user_text,
                                                          sub_question_index, reason)
                        VALUES (?, ?, ?, ?, ?, 'NO_FAQ')
                        """, attemptId, groupId, "검색어 " + index, "원문 조각 " + index, index);
            }
            // V34 이전 형식과 attempt 보관 기간이 끝난 행도 기본 순번 0 → 1로 옮깁니다.
            Long legacyId = jdbc.queryForObject("""
                    INSERT INTO question_log (user_id, user_question) VALUES (?, '이전 형식') RETURNING id
                    """, Long.class, userId);
            Long categoryId = jdbc.queryForObject(
                    "INSERT INTO faq_category (name) VALUES ('순번 전환') RETURNING id", Long.class);
            Long faqId = jdbc.queryForObject("""
                    INSERT INTO faq (category_id, question, answer) VALUES (?, '질문', '답변') RETURNING id
                    """, Long.class, categoryId);
            jdbc.update("INSERT INTO faq_log (question_log_id, faq_id, rank) VALUES (?, ?, 1)", logIds.getFirst(), faqId);

            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").load().migrate();

            assertThat(jdbc.queryForList("SELECT sub_question_index FROM question_log ORDER BY id", Integer.class))
                    .containsExactly(1, 2, 3, 1);
            assertThat(jdbc.queryForList("SELECT sub_question_index FROM unanswered_questions ORDER BY id", Integer.class))
                    .containsExactly(1, 2, 3);
            assertThat(jdbc.queryForList("SELECT id FROM question_log WHERE attempt_id = ? ORDER BY sub_question_index", Long.class, attemptId))
                    .containsExactlyElementsOf(logIds);
            assertThat(jdbc.queryForObject("SELECT question_log_id FROM faq_log WHERE faq_id = ?", Long.class, faqId))
                    .isEqualTo(logIds.getFirst());
            assertThat(jdbc.queryForObject("SELECT sub_question FROM question_log WHERE id = ?", String.class, logIds.getLast()))
                    .isEqualTo("검색어 2");
            assertThat(jdbc.queryForObject("SELECT user_text FROM unanswered_questions WHERE attempt_id = ? AND sub_question_index = 3", String.class, attemptId))
                    .isEqualTo("원문 조각 2");
            assertThat(jdbc.queryForObject("SELECT attempt_id FROM question_log WHERE id = ?", Long.class, legacyId)).isNull();
            assertThat(jdbc.queryForObject("""
                    INSERT INTO question_log (user_id, user_question) VALUES (?, '새 원문')
                    RETURNING sub_question_index
                    """, Integer.class, userId)).isEqualTo(1);
            Long nextAttemptId = attempt(jdbc, userId);
            assertThat(jdbc.queryForObject("""
                    INSERT INTO unanswered_questions (attempt_id, group_id, sub_question, user_text, reason)
                    VALUES (?, ?, '새 검색어', '새 원문', 'NO_FAQ') RETURNING sub_question_index
                    """, Integer.class, nextAttemptId, groupId)).isEqualTo(1);
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
