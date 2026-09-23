package com.ubot.chat.mvp.service;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChatRequestKeyTest {
    private final LocalDateTime createdAt = LocalDateTime.of(2026, 9, 23, 12, 34, 56, 123456000);

    @Test void usesUserQuestionAndOriginalCreationTime() {
        assertThat(ChatIdempotencyKey.create(7, "hello", createdAt))
                .isEqualTo("a32b060139a6839dae27a1d88ad4650b8269444ff0d6db6f22ce815f20207d5a");
    }

    @Test void identicalStoredInputsProduceTheSameKey() {
        assertThat(ChatIdempotencyKey.create(1, "질문", createdAt)).hasSize(64)
                .isEqualTo(ChatIdempotencyKey.create(1, "질문", createdAt));
    }

    @Test void userChangesTheKey() {
        assertThat(ChatIdempotencyKey.create(1, "질문", createdAt))
                .isNotEqualTo(ChatIdempotencyKey.create(2, "질문", createdAt));
    }

    @Test void questionChangesTheKey() {
        assertThat(ChatIdempotencyKey.create(1, "질문", createdAt))
                .isNotEqualTo(ChatIdempotencyKey.create(1, "다른 질문", createdAt));
    }

    @Test void creationTimeChangesTheKey() {
        assertThat(ChatIdempotencyKey.create(1, "질문", createdAt))
                .isNotEqualTo(ChatIdempotencyKey.create(1, "질문", createdAt.plusNanos(1000)));
    }

    @Test void missingInputsCannotGenerateAKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> ChatIdempotencyKey.create(0, "질문", createdAt));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatIdempotencyKey.create(1, " ", createdAt));
        assertThatIllegalArgumentException().isThrownBy(() -> ChatIdempotencyKey.create(1, "질문", null));
    }
}
