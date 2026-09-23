package com.ubot.chat.mvp;

import com.ubot.chat.mvp.config.ChatMvpSettings;
import com.ubot.chat.mvp.domain.ChatAttempt;
import com.ubot.chat.mvp.dto.ChatAttemptResponse;
import com.ubot.chat.mvp.exception.ChatMvpErrorCode;
import com.ubot.chat.mvp.exception.ChatMvpException;
import com.ubot.chat.mvp.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.mvp.repository.QuestionLogRepository;
import com.ubot.chat.mvp.repository.ChatMvpSessionRepository;
import com.ubot.chat.mvp.service.ChatIdempotencyKey;
import com.ubot.chat.mvp.service.ChatRecordService;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.repository.FaqRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatRecordServiceTest {
    private final ChatMvpSessionRepository sessions = mock(ChatMvpSessionRepository.class);
    private final QuestionLogRepository questions = mock(QuestionLogRepository.class);
    private final AnswerAttemptsHistoryRepository attempts = mock(AnswerAttemptsHistoryRepository.class);
    private final FaqRepository faqs = mock(FaqRepository.class);
    private final FaqLogRepository logs = mock(FaqLogRepository.class);
    private final ChatRecordService service = new ChatRecordService(sessions, questions, attempts, faqs, logs,
            new ChatMvpSettings(Duration.ofSeconds(150), 4, 16, 3, .75, "llm", "embedding"));
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 23, 12, 34, 56);
    private static final String KEY = ChatIdempotencyKey.create(1, "질문", CREATED);

    @BeforeEach void ownsSession() { when(sessions.existsOwned(1, 20)).thenReturn(true); }

    private ChatAttempt attempt(int count, String status, String code) {
        return new ChatAttempt(100 + count, 10, 1, 20, "질문", count, status, KEY, null, code, "실패 안내");
    }

    private QuestionLogRepository.Question question(long sessionId) {
        return new QuestionLogRepository.Question(10, 1, sessionId, "질문", CREATED);
    }

    private void ownsQuestion() { when(questions.lockOwned(1, 10)).thenReturn(Optional.of(question(20))); }

    private void assertError(Runnable call, ChatMvpErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ChatMvpException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    @Test void initialAttemptPersistsKeyFromTheSavedQuestionCreationTime() {
        when(questions.create(1, 20, "질문")).thenReturn(question(20));
        when(attempts.create(10, "질문", 1, KEY, "llm", "embedding")).thenReturn(attempt(1, "PENDING", null));
        var claim = service.begin(1, 20, "질문");
        assertThat(claim.created()).isTrue();
        assertThat(claim.attempt().attemptCount()).isEqualTo(1);
        assertThat(claim.attempt().idempotencyKey()).isEqualTo(KEY);
        verify(attempts).create(10, "질문", 1, KEY, "llm", "embedding");
    }

    @Test void firstFailureCanBeRetriedWithTheSameOriginalKey() {
        ownsQuestion();
        when(attempts.latest(10)).thenReturn(Optional.of(attempt(1, "FAIL", "LLM_TIMEOUT")));
        when(attempts.create(10, "질문", 2, KEY, "llm", "embedding")).thenReturn(attempt(2, "PENDING", null));
        var claim = service.retry(1, 20, 10, KEY);
        assertThat(claim.created()).isTrue();
        assertThat(claim.attempt().attemptCount()).isEqualTo(2);
        assertThat(claim.attempt().idempotencyKey()).isEqualTo(KEY);
        verify(questions, never()).create(anyLong(), anyLong(), anyString());
    }

    @Test void secondFailureUsesSameKeyForThirdAndFinalAttempt() {
        ownsQuestion();
        when(attempts.latest(10)).thenReturn(Optional.of(attempt(2, "FAIL", "LLM_TIMEOUT")));
        when(attempts.create(10, "질문", 3, KEY, "llm", "embedding")).thenReturn(attempt(3, "PENDING", null));
        assertThat(service.retry(1, 20, 10, KEY).attempt().idempotencyKey()).isEqualTo(KEY);
        verify(attempts).create(10, "질문", 3, KEY, "llm", "embedding");
    }

    @Test void fourthAttemptIsRejectedBeforeInsert() {
        ownsQuestion();
        when(attempts.latest(10)).thenReturn(Optional.of(attempt(3, "FAIL", "LLM_TIMEOUT")));
        assertError(() -> service.retry(1, 20, 10, KEY), ChatMvpErrorCode.LIMIT_REACHED);
        verify(attempts, never()).create(anyLong(), anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test void anotherUsersKnownKeyDoesNotGrantAccess() {
        assertError(() -> service.retry(2, 20, 10, KEY), ChatMvpErrorCode.NOT_FOUND);
        verifyNoInteractions(attempts);
    }

    @Test void replacingOriginalKeyIsRejectedWithoutStateChanges() {
        ownsQuestion();
        when(attempts.latest(10)).thenReturn(Optional.of(attempt(1, "FAIL", "LLM_TIMEOUT")));
        assertError(() -> service.retry(1, 20, 10, "b".repeat(64)), ChatMvpErrorCode.KEY_CONFLICT);
        verify(attempts, never()).expirePending(anyLong(), anyLong());
        verify(attempts, never()).create(anyLong(), anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test void repeatedRetryWhilePendingReusesRunningAttempt() {
        ownsQuestion();
        when(attempts.latest(10)).thenReturn(Optional.of(attempt(2, "PENDING", null)));
        var claim = service.retry(1, 20, 10, KEY);
        assertThat(claim.created()).isFalse();
        assertThat(claim.attempt().attemptCount()).isEqualTo(2);
        verify(attempts, never()).create(anyLong(), anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test void sameKeyOnSuccessfulQuestionReturnsSavedResult() {
        ownsQuestion();
        when(attempts.latest(10)).thenReturn(Optional.of(attempt(2, "SUCCESS", null)));
        assertThat(service.retry(1, 20, 10, KEY).created()).isFalse();
        verify(attempts, never()).create(anyLong(), anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test void missingFaqDoesNotOfferUselessRetries() {
        ownsQuestion();
        when(attempts.latest(10)).thenReturn(Optional.of(attempt(1, "FAIL", "CHAT_NO_FAQ")));
        assertError(() -> service.retry(1, 20, 10, KEY), ChatMvpErrorCode.NOT_RETRYABLE);
    }

    @Test void expiredAttemptCanUseOriginalKeyToRetry() {
        ownsQuestion();
        when(attempts.latest(10)).thenReturn(Optional.of(attempt(1, "PENDING", null)),
                Optional.of(attempt(1, "FAIL", "CHAT_TIMEOUT")));
        when(attempts.create(10, "질문", 2, KEY, "llm", "embedding")).thenReturn(attempt(2, "PENDING", null));
        assertThat(service.retry(1, 20, 10, KEY).attempt().attemptCount()).isEqualTo(2);
        verify(attempts).expirePending(10, 150000);
    }

    @Test void lateSuccessAfterTimeoutDoesNotOverwriteAnswerOrFaqLogs() {
        ownsQuestion();
        when(attempts.findById(101)).thenReturn(Optional.of(attempt(1, "FAIL", "CHAT_TIMEOUT")));
        when(attempts.finishSuccess(101)).thenReturn(false);
        service.succeed(101, "뒤늦은 답변", List.of());
        verify(questions, never()).saveAnswer(anyLong(), anyString());
        verifyNoInteractions(logs, faqs);
    }

    @Test void thirdFailureStillReturnsOriginalKeyButDisablesRetry() {
        var response = ChatAttemptResponse.from(attempt(3, "FAIL", "LLM_TIMEOUT"));
        assertThat(response.idempotencyKey()).isEqualTo(KEY);
        assertThat(response.retryable()).isFalse();
        assertThat(response.remainingAttempts()).isZero();
        assertThat(response.message()).contains("3회");
    }

    @Test void unknownOrForeignSessionCannotCreateAQuestion() {
        assertError(() -> service.begin(1, 99, "질문"), ChatMvpErrorCode.NOT_FOUND);
        verifyNoInteractions(questions, attempts);
    }

    @Test void anotherUsersQuestionCannotBeReadThroughAnOwnedSession() {
        assertError(() -> service.latest(1, 20, 999), ChatMvpErrorCode.NOT_FOUND);
        verifyNoInteractions(attempts);
    }

    @Test void ownedQuestionCannotBeReadThroughTheWrongSession() {
        when(questions.lockOwned(1, 10)).thenReturn(Optional.of(question(30)));
        assertError(() -> service.latest(1, 20, 10), ChatMvpErrorCode.NOT_FOUND);
        verifyNoInteractions(attempts);
    }

    @Test void ownedQuestionCannotBeRetriedThroughTheWrongSession() {
        when(questions.lockOwned(1, 10)).thenReturn(Optional.of(question(30)));
        assertError(() -> service.retry(1, 20, 10, KEY), ChatMvpErrorCode.NOT_FOUND);
        verifyNoInteractions(attempts);
    }

    @Test void unknownSessionCannotReadOrRetryQuestions() {
        assertError(() -> service.latest(1, 99, 10), ChatMvpErrorCode.NOT_FOUND);
        assertError(() -> service.retry(1, 99, 10, KEY), ChatMvpErrorCode.NOT_FOUND);
        verifyNoInteractions(questions, attempts);
    }

    @Test void successfulLookupIncludesOriginalKeyAndQuestionIds() {
        ownsQuestion();
        when(attempts.latest(10)).thenReturn(Optional.of(attempt(1, "SUCCESS", null)));
        var response = ChatAttemptResponse.from(service.latest(1, 20, 10));
        assertThat(response.sessionId()).isEqualTo(20);
        assertThat(response.questionId()).isEqualTo(10);
        assertThat(response.idempotencyKey()).isEqualTo(KEY);
    }
}
