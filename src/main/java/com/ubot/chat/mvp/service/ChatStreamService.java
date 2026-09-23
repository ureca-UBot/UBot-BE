package com.ubot.chat.mvp.service;

import com.ubot.chat.mvp.config.ChatMvpSettings;
import com.ubot.chat.mvp.domain.ChatAttempt;
import com.ubot.chat.mvp.domain.ChatFailure;
import com.ubot.chat.mvp.dto.ChatAttemptResponse;
import com.ubot.chat.mvp.dto.ChatSessionResponse;
import com.ubot.chat.mvp.exception.ChatMvpErrorCode;
import com.ubot.chat.mvp.exception.ChatMvpException;
import java.io.IOException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class ChatStreamService {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);
    private final ChatRecordService records;
    private final ChatAnswerPipeline pipeline;
    private final ChatJobRunner runner;
    private final ChatMvpSettings settings;

    public ChatSessionResponse createSession(long userId) {
        try {
            return records.createSession(userId);
        } catch (DataAccessException exception) {
            throw new ChatMvpException(ChatMvpErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    public SseEmitter start(long userId, long sessionId, String question) {
        if (!StringUtils.hasText(question) || question.length() > 4000) {
            throw new ChatMvpException(ChatMvpErrorCode.INVALID_REQUEST);
        }
        return submit(userId, sessionId, null, question.strip(), null);
    }

    public SseEmitter retry(long userId, long sessionId, long questionId, String key) {
        if (questionId <= 0 || key == null || !key.matches("[0-9a-f]{64}")) {
            throw new ChatMvpException(ChatMvpErrorCode.INVALID_REQUEST);
        }
        return submit(userId, sessionId, key, null, questionId);
    }

    public ChatAttemptResponse findQuestion(long userId, long sessionId, long questionId) {
        try {
            return ChatAttemptResponse.from(records.latest(userId, sessionId, questionId));
        } catch (DataAccessException exception) {
            throw new ChatMvpException(ChatMvpErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    private SseEmitter submit(long userId, long sessionId, String key, String question, Long questionId) {
        if (!runner.reserve()) { throw new ChatMvpException(ChatMvpErrorCode.BUSY); }
        boolean handedToWorker = false;
        try {
            ChatRecordService.Claim claim = questionId == null ? records.begin(userId, sessionId, question)
                    : records.retry(userId, sessionId, questionId, key);
            if (!claim.created()) {
                ChatAttempt existing = claim.attempt();
                Channel replay = new Channel(settings.timeout().toMillis() + 10000);
                if (existing.pending()) {
                    // The same retry key does not start a second concurrent generation.
                    // The original job keeps running. The client uses the specified status GET.
                    replay.send("processing", ChatAttemptResponse.from(existing));
                    replay.complete();
                } else {
                    replay.finish(ChatAttemptResponse.from(existing));
                }
                return replay.emitter;
            }

            ChatAttempt attempt = claim.attempt();
            Channel channel = new Channel(settings.timeout().toMillis() + 10000);
            channel.send("processing", ChatAttemptResponse.from(attempt));
            Job job = new Job(attempt, channel);
            try {
                job.timeout.set(runner.after(settings.timeout().toMillis(), job::timeout));
                job.heartbeat.set(runner.heartbeat(channel::heartbeat));
                runner.execute(job.task);
                handedToWorker = true;
            } catch (RejectedExecutionException exception) {
                job.cancelTimers();
                job.fail(new ChatFailure("CHAT_WORKER_BUSY", "답변 생성 작업을 시작하지 못했습니다."));
            }
            return channel.emitter;
        } catch (DataAccessException exception) {
            log.warn("채팅 기록 저장소에 접근하지 못했습니다: {}", exception.getClass().getSimpleName());
            throw new ChatMvpException(ChatMvpErrorCode.STORAGE_UNAVAILABLE);
        } finally {
            if (!handedToWorker) { runner.release(); }
        }
    }

    private final class Job {
        private final ChatAttempt attempt;
        private final Channel channel;
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        private final FutureTask<Void> task;

        private Job(ChatAttempt attempt, Channel channel) {
            this.attempt = attempt;
            this.channel = channel;
            this.task = new FutureTask<>(() -> { generate(); return null; });
        }

        private void generate() {
            try {
                ChatAnswerPipeline.Result result = pipeline.generate(attempt.question());
                if (!settled.compareAndSet(false, true)) { return; }
                try {
                    ChatAttempt saved = result.failure() == null
                            ? records.succeed(attempt.attemptId(), result.answer(), result.sources())
                            : records.fail(attempt.attemptId(), result.failure());
                    channel.finish(ChatAttemptResponse.from(saved));
                } catch (RuntimeException exception) {
                    storageFailed(exception);
                }
            } catch (RuntimeException exception) {
                log.error("채팅 답변 생성 실패, attemptId={}", attempt.attemptId(), exception);
                fail(new ChatFailure("CHAT_GENERATION_ERROR", "답변 생성 중 오류가 발생했습니다."));
            } finally {
                cancelTimers();
            }
        }

        private void timeout() {
            if (!settled.compareAndSet(false, true)) { return; }
            task.cancel(true);
            try {
                channel.finish(ChatAttemptResponse.from(records.fail(attempt.attemptId(), ChatFailure.timeout())));
            } catch (RuntimeException exception) {
                storageFailed(exception);
            } finally {
                cancelTimers();
            }
        }

        private void fail(ChatFailure failure) {
            if (!settled.compareAndSet(false, true)) { return; }
            try {
                channel.finish(ChatAttemptResponse.from(records.fail(attempt.attemptId(), failure)));
            } catch (RuntimeException exception) {
                storageFailed(exception);
            } finally {
                cancelTimers();
            }
        }

        private void storageFailed(RuntimeException exception) {
            log.error("채팅 결과 저장 실패, attemptId={}", attempt.attemptId(), exception);
            // Do not claim success or enable a retry when the commit result is unknown.
            // Result lookup will expire an abandoned PENDING record after the deadline.
            channel.finish(ChatAttemptResponse.storageFailure(attempt));
        }

        private void cancelTimers() {
            ScheduledFuture<?> deadline = timeout.get();
            if (deadline != null) { deadline.cancel(false); }
            ScheduledFuture<?> ping = heartbeat.get();
            if (ping != null) { ping.cancel(false); }
        }
    }

    /** A browser disconnect only stops delivery; the job still records its result for authenticated lookup. */
    private static final class Channel {
        private final SseEmitter emitter;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Channel(long timeout) {
            emitter = new SseEmitter(timeout);
            emitter.onCompletion(() -> open.set(false));
            emitter.onError(error -> open.set(false));
            emitter.onTimeout(() -> {
                if (open.getAndSet(false)) { emitter.complete(); }
            });
        }

        private synchronized void send(String event, ChatAttemptResponse response) {
            if (!open.get()) { return; }
            try {
                emitter.send(SseEmitter.event().name(event)
                        .id(response.attemptId() + "-" + event)
                        .data(response, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException exception) {
                // Spring/Servlet owns cleanup after a write error; never turn it into an LLM failure.
                open.set(false);
            }
        }

        private synchronized void heartbeat() {
            if (!open.get()) { return; }
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException exception) {
                open.set(false);
            }
        }

        private synchronized void finish(ChatAttemptResponse response) {
            send(response.success() ? "completed" : "failed", response);
            complete();
        }

        private synchronized void complete() {
            if (open.getAndSet(false)) { emitter.complete(); }
        }
    }
}
