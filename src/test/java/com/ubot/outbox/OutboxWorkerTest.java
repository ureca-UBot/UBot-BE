package com.ubot.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;

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
import com.ubot.unanswered.dto.UnansweredCandidate;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.enums.UnansweredSource;
import com.ubot.unanswered.service.UnansweredQuestionService;

@SpringBootTest(properties = "OUTBOX_POLL_DELAY_MS=3600000")
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("공용 대기열(미응답 질문) 처리 통합 테스트")
class OutboxWorkerTest {

    @Autowired
    private UnansweredQuestionService service;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private EmbeddingProfileService embeddingProfileService;

    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_events");
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

        service.enqueueUnansweredQuestion(attemptId, candidate(1, "유심 교체 비용 알려줘", UnansweredReason.LLM_ABSTAIN, null, 0.8));
        assertThat(count("unanswered_questions")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_type FROM outbox_events WHERE payload->>'attemptId' = ?", String.class,
                String.valueOf(attemptId)))
                .isEqualTo("UNANSWERED_QUESTION");

        worker.processPending();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT reason, sub_question_index, sub_question FROM unanswered_questions WHERE attempt_id = ?", attemptId))
                .containsEntry("reason", "LLM_ABSTAIN")
                .containsEntry("sub_question_index", 1)
                .containsEntry("sub_question", "유심 교체 비용 알려줘");
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("같은 하위 질문을 두 번 넣어도 한 번만 들어가고, 다른 하위 질문은 따로 들어간다")
    void ignoresDuplicateSubQuestion() {
        Long attemptId = attempt("로밍 요금이랑 해지 위약금 알려줘");

        service.enqueueUnansweredQuestion(attemptId, candidate(1, "로밍 요금", UnansweredReason.NO_FAQ, null, null));
        service.enqueueUnansweredQuestion(attemptId, candidate(1, "로밍 요금", UnansweredReason.NO_FAQ, null, null));
        service.enqueueUnansweredQuestion(attemptId, candidate(2, "해지 위약금", UnansweredReason.NO_FAQ, null, null));

        assertThat(count("outbox_events")).isEqualTo(2);
    }

    @Test
    @DisplayName("저장에 실패하면 대기열에 남기고 재시도 횟수를 늘린다")
    void keepsFailedQuestionForRetry() {
        given(embeddingService.embedText(anyString())).willThrow(new RuntimeException("embedding down"));
        Long attemptId = attempt("eSIM 개통 방법");

        service.enqueueUnansweredQuestion(attemptId, candidate(1, "eSIM 개통 방법", UnansweredReason.NO_FAQ, null, null));
        worker.processPending();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT retry_count FROM outbox_events WHERE payload->>'attemptId' = ?", Integer.class, String.valueOf(attemptId)))
                .isEqualTo(1);
        assertThat(count("unanswered_questions")).isZero();
    }

    @Test
    @DisplayName("실패한 작업은 다음 시도 시각 전에는 다시 꺼내지 않고, 그 시각이 지나면 처리한다")
    void waitsUntilNextAttemptAt() {
        given(embeddingService.embedText(anyString())).willThrow(new RuntimeException("embedding down"));
        Long attemptId = attempt("데이터 쉐어링 신청");
        service.enqueueUnansweredQuestion(attemptId, candidate(1, "데이터 쉐어링 신청", UnansweredReason.NO_FAQ, null, null));
        worker.processPending();
        willReturn(vector()).given(embeddingService).embedText(anyString());

        worker.processPending();
        assertThat(count("unanswered_questions")).isZero();

        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = LOCALTIMESTAMP - INTERVAL '1 second'");
        worker.processPending();
        assertThat(count("unanswered_questions")).isEqualTo(1);
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    @DisplayName("재시도 간격은 실패할 때마다 10초에서 두 배씩 늘어난다")
    void doublesRetryDelay() {
        given(embeddingService.embedText(anyString())).willThrow(new RuntimeException("embedding down"));
        Long attemptId = attempt("번호이동 위약금");
        service.enqueueUnansweredQuestion(attemptId, candidate(1, "번호이동 위약금", UnansweredReason.NO_FAQ, null, null));
        jdbcTemplate.update("UPDATE outbox_events SET retry_count = 2");

        worker.processPending();

        Double delaySeconds = jdbcTemplate.queryForObject(
                "SELECT extract(epoch FROM next_attempt_at - LOCALTIMESTAMP) FROM outbox_events", Double.class);
        assertThat(delaySeconds).isBetween(35.0, 40.0);
        assertThat(jdbcTemplate.queryForObject("SELECT retry_count FROM outbox_events", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("대기열에 넣은 발생 경로를 그대로 저장한다")
    void keepsSource() {
        given(embeddingService.embedText(anyString())).willReturn(vector());
        Long attemptId = attempt("요금제 해지 위약금");

        service.enqueueUnansweredQuestion(attemptId, new UnansweredCandidate(
                1, "요금제 해지 위약금", "요금제 해지 위약금", UnansweredReason.INSUFFICIENT_FAQ, null, 0.8,
                UnansweredSource.OUTPUT_GUARD));
        worker.processPending();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT source FROM unanswered_questions WHERE attempt_id = ?", String.class, attemptId))
                .isEqualTo("OUTPUT_GUARD");
    }

    @Test
    @DisplayName("재시도 횟수를 다 쓴 질문은 더 처리하지 않는다")
    void skipsExhaustedQuestion() {
        Long attemptId = attempt("해지 위약금");
        service.enqueueUnansweredQuestion(attemptId, candidate(1, "해지 위약금", UnansweredReason.NO_FAQ, null, null));
        jdbcTemplate.update("UPDATE outbox_events SET retry_count = ?", OutboxWorker.MAX_RETRIES);

        worker.processPending();

        assertThat(count("outbox_events")).isEqualTo(1);
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

    private static UnansweredCandidate candidate(
            int index, String question, UnansweredReason reason, Long bestFaqId, Double bestSimilarity) {
        return new UnansweredCandidate(index, question, question, reason, bestFaqId, bestSimilarity,
                UnansweredSource.RETRIEVAL);
    }
}
