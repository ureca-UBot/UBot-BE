package com.ubot.conversation;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.job.entity.ChatJob;
import com.ubot.chat.job.entity.ChatJobStatus;
import com.ubot.chat.job.repository.ChatJobQueueRepository;
import com.ubot.chat.job.repository.ChatJobRepository;
import com.ubot.chat.job.service.ChatJobCompletionService;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatAttemptsService;
import com.ubot.chat.service.ChatJobOutcome;
import com.ubot.conversation.entity.Conversation;
import com.ubot.conversation.exception.ConversationErrorCode;
import com.ubot.conversation.exception.ConversationException;
import com.ubot.conversation.repository.ConversationRepository;
import com.ubot.conversation.service.ConversationService;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.session.dto.request.HandoffRequestDto;
import com.ubot.counsel.session.entity.Session;
import com.ubot.counsel.session.service.SessionService;
import com.ubot.faq.enums.Intent;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "app.role=api", "app.chat.worker.polling-enabled=false",
        "notification.reservation-reminder-cron=-", "notification.billing-reminder-cron=-",
        "notification.subscription-reminder-cron=-", "notification.usage-reminder-cron=-",
        "data-retention.purge-cron=-",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.ubot.conversation.ConversationDeletionConcurrencyTest$LockSqlInspector"
})
@Import(PgvectorTestConfiguration.class)
@DisplayName("대화 삭제와 답변 시도 동시성 통합 테스트")
class ConversationDeletionConcurrencyTest {
    @Autowired ConversationService service;
    @Autowired ChatAttemptsService attemptsService;
    @Autowired ConversationRepository conversations;
    @Autowired AnswerAttemptsHistoryRepository attempts;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ChatJobRepository jobs;
    @Autowired ChatJobQueueRepository queue;
    @Autowired ChatJobCompletionService completionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionService counselSessions;
    @MockitoBean com.ubot.ranking.scheduler.RankingScheduler rankingScheduler;

    private User member;
    private Conversation conversation;

    @BeforeEach void setup() {
        LockSqlInspector.conversationLocks.clear();
        LocalDateTime now = LocalDateTime.now();
        member = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
                .hashedPassword("test-hash").name("테스트 회원").role(UserRole.USER)
                .createdAt(now).updatedAt(now).build());
        conversation = service.getOrCreateMemberConversation(member.getId(), null);
    }

    @Test
    @DisplayName("삭제가 먼저 대화를 잠그면 뒤따른 질문 접수는 삭제 커밋 후 거절된다")
    void deletionCommitsBeforeSubmission() throws Exception {
        CountDownLatch deletionReady = new CountDownLatch(1);
        CountDownLatch commitDeletion = new CountDownLatch(1);
        CountDownLatch submissionStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> deletion = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                service.deleteConversation(member.getId(), conversation.getPublicId());
                deletionReady.countDown();
                await(commitDeletion);
            }));
            try {
                assertThat(deletionReady.await(10, TimeUnit.SECONDS)).isTrue();
                Future<AnswerAttemptsHistory> submission = executor.submit(() -> {
                    submissionStarted.countDown();
                    return attemptsService.createAnswerAttempt(member.getId(), conversation.getId(), "첫 질문");
                });
                assertThat(submissionStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> submission.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                commitDeletion.countDown();
                deletion.get(10, TimeUnit.SECONDS);
                ExecutionException rejected = assertThrows(ExecutionException.class, () -> submission.get(10, TimeUnit.SECONDS));
                assertThat(rejected.getCause()).isInstanceOfSatisfying(ConversationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ConversationErrorCode.CONVERSATION_NOT_FOUND));
            } finally {
                commitDeletion.countDown();
            }
        }
        assertThat(conversations.findById(conversation.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(attempts.existsByConversationIdAndStatus(conversation.getId(), "PENDING")).isFalse();
    }

    @Test
    @DisplayName("질문 접수가 먼저 대화를 잠그면 삭제는 접수 커밋 후 진행 중 오류로 거절된다")
    void submissionCommitsBeforeDeletion() throws Exception {
        CountDownLatch submissionReady = new CountDownLatch(1);
        CountDownLatch commitSubmission = new CountDownLatch(1);
        CountDownLatch deletionStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> submission = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                attemptsService.createAnswerAttempt(member.getId(), conversation.getId(), "첫 질문");
                submissionReady.countDown();
                await(commitSubmission);
            }));
            try {
                assertThat(submissionReady.await(10, TimeUnit.SECONDS)).isTrue();
                Future<?> deletion = executor.submit(() -> {
                    deletionStarted.countDown();
                    service.deleteConversation(member.getId(), conversation.getPublicId());
                });
                assertThat(deletionStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                commitSubmission.countDown();
                submission.get(10, TimeUnit.SECONDS);
                ExecutionException rejected = assertThrows(ExecutionException.class, () -> deletion.get(10, TimeUnit.SECONDS));
                assertThat(rejected.getCause()).isInstanceOfSatisfying(ConversationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ConversationErrorCode.CONVERSATION_IN_PROGRESS));
            } finally {
                commitSubmission.countDown();
            }
        }
        assertThat(conversations.findById(conversation.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(attempts.existsByConversationIdAndStatus(conversation.getId(), "PENDING")).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Request.class)
    @DisplayName("진행 중 요청은 Worker의 시도 잠금을 기다리지 않고 거절하며 Worker는 답변을 저장한다")
    void rejectsPendingRequestWithoutWaitingForWorker(Request request) throws Exception {
        AnswerAttemptsHistory pending = request == Request.GUEST_RETRY
                ? attemptsService.createGuestAnswerAttempt(service.createGuestConversation(), "진행 중 질문")
                : attemptsService.createAnswerAttempt(member.getId(), conversation.getId(), "진행 중 질문");
        RunningJob running = queueJob(pending);
        CountDownLatch workerLocked = new CountDownLatch(1);
        CountDownLatch finishWorker = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ChatJobCompletionService.Completion> completion = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        jdbc.execute("SET LOCAL lock_timeout = '5s'");
                        jobs.findJobForLock(running.id()).orElseThrow();
                        attempts.findAttemptForLock(pending.getId()).orElseThrow();
                        workerLocked.countDown();
                        await(finishWorker);
                        return complete(running);
                    }));
            try {
                assertThat(workerLocked.await(10, TimeUnit.SECONDS)).isTrue();
                Future<AnswerAttemptsHistory> rejectedRequest = executor.submit(() -> requestAgain(request, pending));
                ExecutionException rejected = assertThrows(ExecutionException.class,
                        () -> rejectedRequest.get(2, TimeUnit.SECONDS));
                assertThat(rejected.getCause()).isInstanceOfSatisfying(ChatException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(
                                request == Request.RESEARCH
                                        ? ChatErrorCode.RESEARCH_NOT_ALLOWED : ChatErrorCode.PROCESSING));
            } finally {
                finishWorker.countDown();
            }
            assertThat(completion.get(10, TimeUnit.SECONDS).recorded()).isTrue();
        }
        assertThat(attempts.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo("SUCCESS");
        assertThat(jobs.findById(running.id()).orElseThrow().getStatus()).isEqualTo(ChatJobStatus.SUCCEEDED);
    }

    @ParameterizedTest
    @EnumSource(value = Request.class, names = {"MEMBER_RETRY", "RESEARCH"})
    @DisplayName("재시도·재검색이 대화를 잠근 동안에도 같은 대화의 Worker가 FK를 검사하고 답변을 저장한다")
    void workerCompletesWhileAnotherRequestHoldsConversationLock(Request request) throws Exception {
        AnswerAttemptsHistory original = attemptsService.createAnswerAttempt(
                member.getId(), conversation.getId(), "이전 질문");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AnswerAttemptsHistory locked = attempts.findAttemptForLock(original.getId()).orElseThrow();
            if (request == Request.RESEARCH) {
                locked.succeed();
            } else {
                // 재시도할 수 있는 실패여야 함. 답변 없음(NO_FAQ)은 재시도해도 같아 막혀 있음
                locked.fail(ChatErrorCode.INTERNAL_ERROR);
            }
        });
        AnswerAttemptsHistory pending = attemptsService.createAnswerAttempt(
                member.getId(), conversation.getId(), "진행 중 질문");
        RunningJob running = queueJob(pending);
        CountDownLatch requestLocked = new CountDownLatch(1);
        CountDownLatch commitRequest = new CountDownLatch(1);
        LockSqlInspector.conversationLocks.clear();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AnswerAttemptsHistory> requestAttempt = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        jdbc.execute("SET LOCAL lock_timeout = '5s'");
                        AnswerAttemptsHistory created = requestAgain(request, original);
                        requestLocked.countDown();
                        await(commitRequest);
                        return created;
                    }));
            try {
                assertThat(requestLocked.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(LockSqlInspector.conversationLocks)
                        .anySatisfy(sql -> assertThat(sql).contains("for no key update"));
                Future<ChatJobCompletionService.Completion> completion = executor.submit(() ->
                        new TransactionTemplate(transactionManager).execute(status -> {
                            jdbc.execute("SET LOCAL lock_timeout = '5s'");
                            return complete(running);
                        }));
                assertThat(completion.get(10, TimeUnit.SECONDS).recorded()).isTrue();
            } finally {
                commitRequest.countDown();
            }
            assertThat(requestAttempt.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo("PENDING");
        }
        assertThat(attempts.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo("SUCCESS");
        assertThat(jobs.findById(running.id()).orElseThrow().getStatus()).isEqualTo(ChatJobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("삭제가 먼저 대화를 잠그면 상담 연결은 삭제 커밋 후 거절된다")
    void deletionCommitsBeforeCounselHandoff() throws Exception {
        CountDownLatch deletionReady = new CountDownLatch(1);
        CountDownLatch commitDeletion = new CountDownLatch(1);
        CountDownLatch handoffStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> deletion = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                service.deleteConversation(member.getId(), conversation.getPublicId());
                deletionReady.countDown();
                await(commitDeletion);
            }));
            try {
                assertThat(deletionReady.await(10, TimeUnit.SECONDS)).isTrue();
                Future<Session> handoff = executor.submit(() -> {
                    handoffStarted.countDown();
                    return counselSessions.createHandoff(new Caller(member.getId(), UserRole.USER),
                            new HandoffRequestDto(UUID.randomUUID(), conversation.getPublicId(), List.of()));
                });
                assertThat(handoffStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> handoff.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                commitDeletion.countDown();
                deletion.get(10, TimeUnit.SECONDS);
                ExecutionException rejected = assertThrows(ExecutionException.class, () -> handoff.get(10, TimeUnit.SECONDS));
                assertThat(rejected.getCause()).isInstanceOfSatisfying(ConversationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ConversationErrorCode.CONVERSATION_NOT_FOUND));
            } finally {
                commitDeletion.countDown();
            }
        }
        assertThat(conversations.findById(conversation.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(conversations.existsCounselSessionByPublicId(conversation.getPublicId())).isFalse();
    }

    @Test
    @DisplayName("상담 연결이 먼저 대화를 잠그면 삭제는 연결 커밋 후 상담 이력 오류로 거절된다")
    void counselHandoffCommitsBeforeDeletion() throws Exception {
        CountDownLatch handoffReady = new CountDownLatch(1);
        CountDownLatch commitHandoff = new CountDownLatch(1);
        CountDownLatch deletionStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Session> handoff = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                Session created = counselSessions.createHandoff(new Caller(member.getId(), UserRole.USER),
                        new HandoffRequestDto(UUID.randomUUID(), conversation.getPublicId(), List.of()));
                handoffReady.countDown();
                await(commitHandoff);
                return created;
            }));
            try {
                assertThat(handoffReady.await(10, TimeUnit.SECONDS)).isTrue();
                Future<?> deletion = executor.submit(() -> {
                    deletionStarted.countDown();
                    service.deleteConversation(member.getId(), conversation.getPublicId());
                });
                assertThat(deletionStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> deletion.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                commitHandoff.countDown();
                assertThat(handoff.get(10, TimeUnit.SECONDS).conversationId()).isEqualTo(conversation.getPublicId());
                ExecutionException rejected = assertThrows(ExecutionException.class, () -> deletion.get(10, TimeUnit.SECONDS));
                assertThat(rejected.getCause()).isInstanceOfSatisfying(ConversationException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ConversationErrorCode.CONVERSATION_HAS_COUNSEL_HISTORY));
            } finally {
                commitHandoff.countDown();
            }
        }
        assertThat(conversations.findById(conversation.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(conversations.existsCounselSessionByPublicId(conversation.getPublicId())).isTrue();
    }

    private AnswerAttemptsHistory requestAgain(Request request, AnswerAttemptsHistory source) {
        return switch (request) {
            case MEMBER_RETRY -> attemptsService.createRetryAttempt(member.getId(), source.getIdempotencyKey());
            case GUEST_RETRY -> attemptsService.createGuestRetryAttempt(
                    source.getConversationId(), source.getIdempotencyKey());
            case RESEARCH -> attemptsService.createResearchAttempt(
                    member.getId(), source.getIdempotencyKey(), Intent.GENERAL);
        };
    }

    private RunningJob queueJob(AnswerAttemptsHistory attempt) {
        ChatJob job = jobs.saveAndFlush(ChatJob.queue(attempt.getId()));
        LocalDateTime startedAt = queue.claim(job.getId(),
                LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)).orElseThrow().startedAt();
        return new RunningJob(job.getId(), startedAt);
    }

    private ChatJobCompletionService.Completion complete(RunningJob running) {
        return completionService.complete(running.id(), running.startedAt(),
                new ChatJobOutcome("테스트 답변", List.of(), null, null, List.of()), null);
    }

    private record RunningJob(Long id, LocalDateTime startedAt) {}

    private enum Request {
        MEMBER_RETRY, GUEST_RETRY, RESEARCH
    }

    public static class LockSqlInspector implements StatementInspector {
        private static final ConcurrentLinkedQueue<String> conversationLocks = new ConcurrentLinkedQueue<>();

        @Override
        public String inspect(String sql) {
            String normalized = sql.toLowerCase(Locale.ROOT);
            if (normalized.contains("from conversations") && normalized.contains(" for ")) {
                conversationLocks.add(normalized);
            }
            return sql;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
