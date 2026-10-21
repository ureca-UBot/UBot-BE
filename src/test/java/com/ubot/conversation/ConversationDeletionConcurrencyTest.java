package com.ubot.conversation;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.service.ChatAttemptsService;
import com.ubot.conversation.entity.Conversation;
import com.ubot.conversation.exception.ConversationErrorCode;
import com.ubot.conversation.exception.ConversationException;
import com.ubot.conversation.repository.ConversationRepository;
import com.ubot.conversation.service.ConversationService;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
        "data-retention.purge-cron=-"
})
@Import(PgvectorTestConfiguration.class)
@DisplayName("대화 삭제와 질문 접수 동시성 통합 테스트")
class ConversationDeletionConcurrencyTest {
    @Autowired ConversationService service;
    @Autowired ChatAttemptsService attemptsService;
    @Autowired ConversationRepository conversations;
    @Autowired AnswerAttemptsHistoryRepository attempts;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean com.ubot.ranking.scheduler.RankingScheduler rankingScheduler;

    private User member;
    private Conversation conversation;

    @BeforeEach void setup() {
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

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
