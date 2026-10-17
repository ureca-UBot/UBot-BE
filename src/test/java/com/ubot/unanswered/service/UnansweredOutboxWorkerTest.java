package com.ubot.unanswered.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.unanswered.enums.UnansweredReason;

@SpringBootTest(properties = "UNANSWERED_OUTBOX_POLL_DELAY_MS=3600000")
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("미응답 질문 대기열 처리 통합 테스트")
class UnansweredOutboxWorkerTest {

    @Autowired
    private UnansweredQuestionService service;

    @Autowired
    private UnansweredOutboxWorker worker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private EmbeddingProfileService embeddingProfileService;

    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM unanswered_question_outbox");
        jdbcTemplate.update("DELETE FROM unanswered_questions");
        jdbcTemplate.update("DELETE FROM unanswered_question_groups");
        jdbcTemplate.update("""
                INSERT INTO embedding_profiles (provider, model_name, dimensions, profile_version)
                VALUES ('ollama', 'outbox-test', 1024, 1)
                ON CONFLICT (provider, model_name, dimensions, profile_version) DO NOTHING
                """);
        Long profileId = jdbcTemplate.queryForObject(
                "SELECT profile_id FROM embedding_profiles WHERE model_name = 'outbox-test'", Long.class);
        given(embeddingProfileService.getCurrentProfileId()).willReturn(profileId);
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, name) VALUES (?, 'hash', '테스터') RETURNING user_id",
                Long.class, UUID.randomUUID() + "@test.com");
    }

    @Test
    @DisplayName("대기열에 넣은 질문은 Worker가 미응답 질문으로 저장하고 대기열에서 지운다")
    void savesQueuedQuestion() {
        given(embeddingService.embedText(anyString())).willReturn(vector());
        Long attemptId = attempt("유심 교체 비용 알려줘");

        service.enqueueUnansweredQuestion(attemptId, "유심 교체 비용 알려줘", UnansweredReason.LLM_ABSTAIN, null, 0.8);
        assertThat(count("unanswered_questions")).isZero();

        worker.processPending();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM unanswered_questions WHERE attempt_id = ?", String.class, attemptId))
                .isEqualTo("LLM_ABSTAIN");
        assertThat(count("unanswered_question_outbox")).isZero();
    }

    @Test
    @DisplayName("같은 시도를 두 번 넣어도 한 번만 저장된다")
    void ignoresDuplicateAttempt() {
        Long attemptId = attempt("로밍 요금 알려줘");

        service.enqueueUnansweredQuestion(attemptId, "로밍 요금 알려줘", UnansweredReason.NO_FAQ, null, null);
        service.enqueueUnansweredQuestion(attemptId, "로밍 요금 알려줘", UnansweredReason.NO_FAQ, null, null);

        assertThat(count("unanswered_question_outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("저장에 실패하면 대기열에 남기고 재시도 횟수를 늘린다")
    void keepsFailedQuestionForRetry() {
        given(embeddingService.embedText(anyString())).willThrow(new RuntimeException("embedding down"));
        Long attemptId = attempt("eSIM 개통 방법");

        service.enqueueUnansweredQuestion(attemptId, "eSIM 개통 방법", UnansweredReason.NO_FAQ, null, null);
        worker.processPending();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT retry_count FROM unanswered_question_outbox WHERE attempt_id = ?", Integer.class, attemptId))
                .isEqualTo(1);
        assertThat(count("unanswered_questions")).isZero();
    }

    @Test
    @DisplayName("재시도 횟수를 다 쓴 질문은 더 처리하지 않는다")
    void skipsExhaustedQuestion() {
        Long attemptId = attempt("해지 위약금");
        service.enqueueUnansweredQuestion(attemptId, "해지 위약금", UnansweredReason.NO_FAQ, null, null);
        jdbcTemplate.update("UPDATE unanswered_question_outbox SET retry_count = ?", UnansweredOutboxWorker.MAX_RETRIES);

        worker.processPending();

        assertThat(count("unanswered_question_outbox")).isEqualTo(1);
        assertThat(count("unanswered_questions")).isZero();
    }

    private Long attempt(String question) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO answer_attempts_history (user_id, question, attempt_count, status, idempotency_key) VALUES (?, ?, 1, 'SUCCESS', ?) RETURNING attempt_id",
                Long.class, userId, question, UUID.randomUUID().toString());
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static PGvector vector() {
        float[] values = new float[1024];
        values[0] = 1.0f;
        return new PGvector(values);
    }
}
