package com.ubot.chat.service;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.client.LlmClient;
import com.ubot.unanswered.repository.UnansweredQuestionGroupRepository;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 재검색 attempt 생성 규칙을 실제 JPA·부분 유니크 인덱스·행 잠금과 함께 검증합니다. */
@SpringBootTest(properties = {
        "prompt.faq.system-location=classpath:prompts/test-faq-system.txt",
        "prompt.faq.user-location=classpath:prompts/test-faq-user.txt" })
@Import(PgvectorTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatAttemptsServiceResearchTest {
    @Autowired
    ChatAttemptsService attemptsService;
    @Autowired
    AnswerAttemptsHistoryRepository attempts;
    @Autowired
    QuestionLogRepository questions;
    @Autowired
    FaqLogRepository faqLogs;
    @Autowired
    UnansweredQuestionRepository unansweredQuestions;
    @Autowired
    UnansweredQuestionGroupRepository unansweredQuestionGroups;
    @Autowired
    UserRepository users;
    @MockitoBean
    FaqVectorService vector;
    @MockitoBean
    LlmClient llm;

    User user;

    @BeforeEach
    void setup() {
        unansweredQuestions.deleteAllInBatch();
        unansweredQuestionGroups.deleteAllInBatch();
        faqLogs.deleteAllInBatch();
        questions.deleteAllInBatch();
        attempts.deleteAllInBatch();
        user = saveUser();
    }

    @Test
    void successfulOriginalCreatesIndependentResearchAttempt() {
        var source = saveSuccess(user);

        var research = attemptsService.createResearchAttempt(user.getId(), source.getIdempotencyKey(),
                Intent.STORE_DATA);

        assertThat(research.getId()).isNotEqualTo(source.getId());
        assertThat(research.getSourceAttemptId()).isEqualTo(source.getId());
        assertThat(research.getIntent()).isEqualTo(Intent.STORE_DATA);
        assertThat(research.getUserId()).isEqualTo(user.getId());
        assertThat(research.getQuestion()).isEqualTo(source.getQuestion());
        assertThat(research.getAttemptCount()).isEqualTo(1);
        assertThat(research.getStatus()).isEqualTo("PENDING");
        // 원본의 재시도 체인과 섞이지 않도록 멱등키는 새로 발급되고, 기존 키와 같은 64자 형식입니다.
        assertThat(research.getIdempotencyKey()).isNotEqualTo(source.getIdempotencyKey()).hasSize(64);
    }

    @Test
    void eachIntentIsAllowedOnceAndSameIntentIsRejected() {
        var source = saveSuccess(user);

        for (Intent intent : Intent.values()) {
            attemptsService.createResearchAttempt(user.getId(), source.getIdempotencyKey(), intent);
        }

        assertRejected(() -> attemptsService.createResearchAttempt(
                user.getId(), source.getIdempotencyKey(), Intent.GENERAL), ChatErrorCode.ALREADY_RESEARCHED);
        assertThat(attempts.findAll().stream().filter(a -> a.getSourceAttemptId() != null))
                .hasSize(Intent.values().length);
    }

    @Test
    void failedOriginalCannotBeResearched() {
        var failed = new AnswerAttemptsHistory(user.getId(), "질문", 1, "f-" + UUID.randomUUID(),
                LocalDateTime.now(), "", "");
        failed.fail(ChatErrorCode.VECTOR_SEARCH_FAILED);
        attempts.saveAndFlush(failed);

        assertRejected(() -> attemptsService.createResearchAttempt(
                user.getId(), failed.getIdempotencyKey(), Intent.STORE_DATA), ChatErrorCode.RESEARCH_NOT_ALLOWED);
    }

    @Test
    void pendingOriginalCannotBeResearched() {
        var pending = attempts.saveAndFlush(new AnswerAttemptsHistory(user.getId(), "질문", 1,
                "p-" + UUID.randomUUID(), LocalDateTime.now(), "", ""));

        assertRejected(() -> attemptsService.createResearchAttempt(
                user.getId(), pending.getIdempotencyKey(), Intent.STORE_DATA), ChatErrorCode.RESEARCH_NOT_ALLOWED);
    }

    @Test
    void researchAttemptCannotBeResearchedAgain() {
        var source = saveSuccess(user);
        var research = attemptsService.createResearchAttempt(user.getId(), source.getIdempotencyKey(),
                Intent.STORE_DATA);
        research.succeed();
        attempts.saveAndFlush(research);

        assertRejected(() -> attemptsService.createResearchAttempt(
                user.getId(), research.getIdempotencyKey(), Intent.USER_DATA), ChatErrorCode.RESEARCH_NOT_ALLOWED);
    }

    @Test
    void researchResolvesTheSuccessfulAttemptOfARetryChain() {
        String key = "chain-" + UUID.randomUUID();
        var first = new AnswerAttemptsHistory(user.getId(), "질문", 1, key, LocalDateTime.now(), "", "");
        first.fail(ChatErrorCode.VECTOR_SEARCH_FAILED);
        attempts.saveAndFlush(first);
        var second = new AnswerAttemptsHistory(user.getId(), "질문", 2, key, LocalDateTime.now(), "", "");
        second.succeed();
        attempts.saveAndFlush(second);

        var research = attemptsService.createResearchAttempt(user.getId(), key, Intent.USER_DATA);

        assertThat(research.getSourceAttemptId()).isEqualTo(second.getId());
    }

    @Test
    void unknownKeyAndAnotherUsersKeyAreNotFound() {
        var source = saveSuccess(user);
        var other = saveUser();

        assertRejected(() -> attemptsService.createResearchAttempt(
                user.getId(), "unknown-key", Intent.STORE_DATA), ChatErrorCode.ATTEMPT_NOT_FOUND);
        assertRejected(() -> attemptsService.createResearchAttempt(
                other.getId(), source.getIdempotencyKey(), Intent.STORE_DATA), ChatErrorCode.ATTEMPT_NOT_FOUND);
        assertThat(attempts.findAll().stream().filter(a -> a.getSourceAttemptId() != null)).isEmpty();
    }

    @Test
    void concurrentSameIntentCreatesOnlyOneResearchAttempt() throws Exception {
        var source = saveSuccess(user);
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> results = new ArrayList<>();
        try {
            List<Future<String>> futures = IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                start.await();
                try {
                    attemptsService.createResearchAttempt(user.getId(), source.getIdempotencyKey(), Intent.USER_DATA);
                    return "OK";
                } catch (ChatException exception) {
                    return exception.getErrorCode().getCode();
                }
            })).toList();
            start.countDown();
            for (Future<String> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).containsExactlyInAnyOrder("OK", ChatErrorCode.ALREADY_RESEARCHED.getCode());
        assertThat(attempts.findAll().stream().filter(a -> a.getSourceAttemptId() != null)).hasSize(1);
    }

    private User saveUser() {
        return users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
                .hashedPassword("test-hash").name("재검색 테스트").role(UserRole.USER)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    private AnswerAttemptsHistory saveSuccess(User owner) {
        var attempt = new AnswerAttemptsHistory(owner.getId(), "유심 재발급", 1, "s-" + UUID.randomUUID(),
                LocalDateTime.now(), "", "");
        attempt.succeed();
        return attempts.saveAndFlush(attempt);
    }

    private static void assertRejected(ThrowingCallable call, ChatErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ChatException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}