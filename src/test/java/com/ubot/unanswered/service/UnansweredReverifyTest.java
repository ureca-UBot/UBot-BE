package com.ubot.unanswered.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.util.List;
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
import com.ubot.faq.dto.request.FaqCreateRequestDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.service.FaqService;
import com.ubot.outbox.OutboxWorker;
import com.ubot.unanswered.dto.response.UnansweredReverificationResponseDto;
import com.ubot.unanswered.repository.UnansweredReverifyRepository;

@SpringBootTest(properties = "OUTBOX_POLL_DELAY_MS=3600000")
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("FAQ 등록 후 미응답 질문 자동 재검증 통합 테스트")
class UnansweredReverifyTest {

    @Autowired
    private FaqService faqService;

    @Autowired
    private UnansweredGroupService groupService;

    @Autowired
    private UnansweredReverifyRepository reverifyRepository;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private EmbeddingProfileService embeddingProfileService;

    private Long profileId;
    private Long adminId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM unanswered_question_reverifications");
        jdbcTemplate.update("DELETE FROM unanswered_questions");
        jdbcTemplate.update("DELETE FROM unanswered_question_groups");
        jdbcTemplate.update("""
                INSERT INTO embedding_profiles (provider, model_name, dimensions, profile_version)
                VALUES ('ollama', 'outbox-test', 1024, 1)
                ON CONFLICT (provider, model_name, dimensions, profile_version) DO NOTHING
                """);
        profileId = jdbcTemplate.queryForObject(
                "SELECT profile_id FROM embedding_profiles WHERE model_name = 'outbox-test'", Long.class);
        given(embeddingProfileService.getCurrentProfileId()).willReturn(profileId);
        adminId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, name, role) VALUES (?, 'hash', '관리자', 'ADMIN') RETURNING user_id",
                Long.class, UUID.randomUUID() + "@test.com");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO faq_category (name) VALUES (?) RETURNING id", Long.class, "재검증-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("FAQ를 등록하면 처리 대기 묶음의 질문 중 새 FAQ로 기준을 넘는 질문을 전후 유사도와 함께 기록한다")
    void recordsQuestionsResolvableByNewFaq() {
        Long groupId = group("PENDING");
        Long questionId = question(groupId, "유심 분실 신고", 0.5, vector(0));
        given(embeddingService.embedText(anyString())).willReturn(vector(0));

        Long faqId = faqService.createFaq(
                new FaqCreateRequestDto(categoryId, "유심을 잃어버렸어요", "고객센터로 분실 신고하세요.", Intent.GENERAL), adminId).id();
        worker.processPending();

        List<UnansweredReverificationResponseDto> reverifications = groupService.getUnansweredGroup(groupId).reverifications();
        assertThat(reverifications).singleElement().satisfies(item -> {
            assertThat(item.questionId()).isEqualTo(questionId);
            assertThat(item.faqId()).isEqualTo(faqId);
            assertThat(item.beforeSimilarity()).isEqualTo(0.5);
            assertThat(item.afterSimilarity()).isGreaterThan(0.99);
        });
        assertThat(reverifyRepository.findSummaries(List.of(groupId)).get(groupId))
                .isEqualTo(new UnansweredReverifyRepository.ReverificationSummary(faqId, 1));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    @DisplayName("기준보다 먼 질문과 처리가 끝난 묶음의 질문은 기록하지 않는다")
    void skipsDistantQuestionsAndClosedGroups() {
        Long pending = group("PENDING");
        question(pending, "로밍 요금", 0.4, vector(1));
        Long approved = group("APPROVED");
        question(approved, "유심 분실", 0.4, vector(0));
        given(embeddingService.embedText(anyString())).willReturn(vector(0));

        faqService.createFaq(
                new FaqCreateRequestDto(categoryId, "유심을 잃어버렸어요", "분실 신고하세요.", Intent.GENERAL), adminId);
        worker.processPending();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM unanswered_question_reverifications", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("FAQ를 삭제하면 그 FAQ로 기록한 재검증 결과도 지운다")
    void removesResultsWhenFaqDeleted() {
        Long groupId = group("ON_HOLD");
        question(groupId, "유심 분실 신고", null, vector(0));
        given(embeddingService.embedText(anyString())).willReturn(vector(0));
        Long faqId = faqService.createFaq(
                new FaqCreateRequestDto(categoryId, "유심을 잃어버렸어요", "분실 신고하세요.", Intent.GENERAL), adminId).id();
        worker.processPending();
        assertThat(groupService.getUnansweredGroup(groupId).reverifications()).hasSize(1);

        faqService.deleteFaq(faqId);
        worker.processPending();

        assertThat(groupService.getUnansweredGroup(groupId).reverifications()).isEmpty();
    }

    private Long group(String status) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO unanswered_question_groups (representative_question, question_count, status, last_occurred_at)
                VALUES ('대표 질문', 1, ?, now())
                RETURNING id
                """,
                Long.class, status);
    }

    private Long question(Long groupId, String text, Double bestSimilarity, PGvector vector) {
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, name) VALUES (?, 'hash', '사용자') RETURNING user_id",
                Long.class, UUID.randomUUID() + "@test.com");
        Long attemptId = jdbcTemplate.queryForObject(
                "INSERT INTO answer_attempts_history (user_id, question, attempt_count, status, idempotency_key) VALUES (?, ?, 1, 'FAIL', ?) RETURNING attempt_id",
                Long.class, userId, text, UUID.randomUUID().toString());
        Long questionId = jdbcTemplate.queryForObject(
                """
                INSERT INTO unanswered_questions (attempt_id, group_id, sub_question, user_text, reason, best_similarity)
                VALUES (?, ?, ?, ?, 'INSUFFICIENT_FAQ', ?)
                RETURNING id
                """,
                Long.class, attemptId, groupId, text, text, bestSimilarity);
        jdbcTemplate.update(
                "INSERT INTO unanswered_question_embeddings (question_id, profile_id, vector) VALUES (?, ?, ?)",
                questionId, profileId, vector);
        return questionId;
    }

    private static PGvector vector(int axis) {
        float[] values = new float[1024];
        values[axis] = 1.0f;
        return new PGvector(values);
    }
}
