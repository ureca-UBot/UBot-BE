package com.ubot.chat.mvp.service;

import com.ubot.chat.mvp.config.ChatMvpSettings;
import com.ubot.chat.mvp.domain.ChatAttempt;
import com.ubot.chat.mvp.domain.ChatFailure;
import com.ubot.chat.mvp.dto.ChatAttemptResponse;
import com.ubot.chat.mvp.dto.ChatSessionResponse;
import com.ubot.chat.mvp.exception.ChatMvpErrorCode;
import com.ubot.chat.mvp.exception.ChatMvpException;
import com.ubot.chat.mvp.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.mvp.repository.QuestionLogRepository;
import com.ubot.chat.mvp.repository.ChatMvpSessionRepository;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.entity.FaqLog;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.repository.FaqRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only database operations belong here. Never call embedding or LLM while holding these transactions. */
@Service
@RequiredArgsConstructor
public class ChatRecordService {
    private final ChatMvpSessionRepository sessions;
    private final QuestionLogRepository questions;
    private final AnswerAttemptsHistoryRepository attempts;
    private final FaqRepository faqs;
    private final FaqLogRepository faqLogs;
    private final ChatMvpSettings settings;

    public record Claim(ChatAttempt attempt, boolean created) {}

    @Transactional(timeout = 10)
    public ChatSessionResponse createSession(long userId) {
        if (userId <= 0) { throw new ChatMvpException(ChatMvpErrorCode.LOGIN_REQUIRED); }
        return sessions.create(userId);
    }

    @Transactional(timeout = 10)
    public Claim begin(long userId, long sessionId, String question) {
        requireSession(userId, sessionId);
        var saved = questions.create(userId, sessionId, question);
        String key = ChatIdempotencyKey.create(saved.userId(), saved.text(), saved.createdAt());
        return new Claim(attempts.create(saved.id(), saved.text(), 1, key,
                settings.llmModel(), settings.embeddingModel()), true);
    }

    @Transactional(timeout = 10, noRollbackFor = ChatMvpException.class)
    public Claim retry(long userId, long sessionId, long questionId, String key) {
        requireSession(userId, sessionId);
        // Serializes distinct retry requests for the same question, across server instances.
        var question = lockQuestion(userId, sessionId, questionId);
        var previous = attempts.latest(questionId).orElseThrow(() ->
                new ChatMvpException(ChatMvpErrorCode.NOT_FOUND));
        // The client must return the server's original question key, unchanged.
        if (!previous.idempotencyKey().equals(key)) { throw new ChatMvpException(ChatMvpErrorCode.KEY_CONFLICT); }
        attempts.expirePending(questionId, settings.timeout().toMillis());
        previous = attempts.latest(questionId).orElseThrow();
        // A repeated retry while running/completed does not consume another attempt.
        if (previous.pending() || previous.successful()) { return new Claim(previous, false); }
        if (previous.attemptCount() >= ChatAttemptResponse.MAX_ATTEMPTS) {
            throw new ChatMvpException(ChatMvpErrorCode.LIMIT_REACHED);
        }
        if (!ChatFailure.isRetryable(previous.errorCode())) {
            throw new ChatMvpException(ChatMvpErrorCode.NOT_RETRYABLE);
        }
        return new Claim(attempts.create(questionId, question.text(), previous.attemptCount() + 1,
                previous.idempotencyKey(), settings.llmModel(), settings.embeddingModel()), true);
    }

    @Transactional(timeout = 10)
    public ChatAttempt latest(long userId, long sessionId, long questionId) {
        requireSession(userId, sessionId);
        lockQuestion(userId, sessionId, questionId);
        attempts.expirePending(questionId, settings.timeout().toMillis());
        return attempts.latest(questionId).orElseThrow(() ->
                new ChatMvpException(ChatMvpErrorCode.NOT_FOUND));
    }

    @Transactional(timeout = 10)
    public ChatAttempt succeed(long attemptId, String answer, List<FaqSearchResponseDto> sources) {
        var attempt = attempts.findById(attemptId).orElseThrow();
        lockAndExpire(attempt);
        // A timed-out attempt must not overwrite the result of a newer retry.
        if (attempts.finishSuccess(attemptId)) {
            questions.saveAnswer(attempt.questionId(), answer);
            List<FaqLog> logs = new ArrayList<>();
            for (int index = 0; index < sources.size(); index++) {
                var source = sources.get(index);
                logs.add(FaqLog.builder().questionLogId(attempt.questionId())
                        .faq(faqs.getReferenceById(source.faqId())).rank(index + 1)
                        .similarity(source.similarityScore()).createdAt(LocalDateTime.now()).build());
            }
            faqLogs.saveAll(logs);
            // Flush within this transaction so a log failure rolls back the success and answer too.
            faqLogs.flush();
        }
        return attempts.findById(attemptId).orElseThrow();
    }

    @Transactional(timeout = 10)
    public ChatAttempt fail(long attemptId, ChatFailure failure) {
        var attempt = attempts.findById(attemptId).orElseThrow();
        lockAndExpire(attempt);
        attempts.finishFailure(attemptId, failure);
        return attempts.findById(attemptId).orElseThrow();
    }

    private void lockAndExpire(ChatAttempt attempt) {
        questions.lockOwned(attempt.userId(), attempt.questionId()).orElseThrow(() ->
                new ChatMvpException(ChatMvpErrorCode.NOT_FOUND));
        attempts.expirePending(attempt.questionId(), settings.timeout().toMillis());
    }

    private void requireSession(long userId, long sessionId) {
        if (sessionId <= 0 || !sessions.existsOwned(userId, sessionId)) {
            throw new ChatMvpException(ChatMvpErrorCode.NOT_FOUND);
        }
    }

    private QuestionLogRepository.Question lockQuestion(long userId, long sessionId, long questionId) {
        return questions.lockOwned(userId, questionId)
                .filter(question -> question.sessionId() == sessionId)
                .orElseThrow(() -> new ChatMvpException(ChatMvpErrorCode.NOT_FOUND));
    }

}
