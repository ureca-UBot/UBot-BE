package com.ubot.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.faq.enums.Intent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("답변 시도 이력 엔티티 테스트")
class AnswerAttemptsHistoryTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 7, 12, 0);

    private AnswerAttemptsHistory source() {
        return AnswerAttemptsHistory.builder()
                .id(10L).userId(1L).conversationId(7L).question("질문").attemptCount(2)
                .status("SUCCESS").idempotencyKey("a".repeat(64)).llmModel("old-llm").embeddingModel("old-embedding")
                .build();
    }

    @Test
    @DisplayName("재검색 attempt는 원본의 질문·소유자·대화를 이어받고 새 멱등키의 대기 상태 첫 시도가 된다")
    void createResearchAttempt_inheritsSourceAndStartsPendingFirstAttempt() {
        AnswerAttemptsHistory attempt = AnswerAttemptsHistory.createResearchAttempt(
                source(), Intent.STORE_DATA, "b".repeat(64), CREATED_AT, "llm", "embedding");

        assertThat(attempt.getId()).isNull();
        assertThat(attempt.getUserId()).isEqualTo(1L);
        assertThat(attempt.getConversationId()).isEqualTo(7L);
        assertThat(attempt.getQuestion()).isEqualTo("질문");
        assertThat(attempt.getAttemptCount()).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo("PENDING");
        assertThat(attempt.getIdempotencyKey()).isEqualTo("b".repeat(64));
        assertThat(attempt.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(attempt.getSourceAttemptId()).isEqualTo(10L);
        assertThat(attempt.getIntent()).isEqualTo(Intent.STORE_DATA);
        assertThat(attempt.isResearch()).isTrue();
    }

    @Test
    @DisplayName("재검색 attempt는 원본의 모델이 아니라 이번에 사용하는 모델을 기록한다")
    void createResearchAttempt_recordsCurrentModels() {
        AnswerAttemptsHistory attempt = AnswerAttemptsHistory.createResearchAttempt(
                source(), Intent.GENERAL, "b".repeat(64), CREATED_AT, "llm", "embedding");

        assertThat(attempt.getLlmModel()).isEqualTo("llm");
        assertThat(attempt.getEmbeddingModel()).isEqualTo("embedding");
    }

    @Test
    @DisplayName("일반 attempt는 재검색 attempt가 아니다")
    void regularAttempt_isNotResearch() {
        AnswerAttemptsHistory attempt = new AnswerAttemptsHistory(
                1L, "질문", 1, "c".repeat(64), CREATED_AT, "llm", "embedding");

        assertThat(attempt.getSourceAttemptId()).isNull();
        assertThat(attempt.getIntent()).isNull();
        assertThat(attempt.isResearch()).isFalse();
    }

    @Test
    @DisplayName("저장되지 않은 원본, 비회원 원본, intent 없음은 재검색 attempt를 만들 수 없다")
    void createResearchAttempt_rejectsInvalidInput() {
        var unsaved = AnswerAttemptsHistory.builder().userId(1L).question("질문").build();
        var guest = AnswerAttemptsHistory.builder().id(10L).question("질문").build();

        assertThatThrownBy(() -> AnswerAttemptsHistory.createResearchAttempt(
                unsaved, Intent.GENERAL, "b".repeat(64), CREATED_AT, "llm", "embedding"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AnswerAttemptsHistory.createResearchAttempt(
                guest, Intent.GENERAL, "b".repeat(64), CREATED_AT, "llm", "embedding"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AnswerAttemptsHistory.createResearchAttempt(
                source(), null, "b".repeat(64), CREATED_AT, "llm", "embedding"))
                .isInstanceOf(NullPointerException.class);
    }
}