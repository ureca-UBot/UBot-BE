package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.faq.dto.request.FaqUpdateRequestDto;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "faq-cache.enabled=true")
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FaqOfficialAnswerIntegrationTest {
    @Autowired private FaqCacheService answers;
    @Autowired private FaqService faqs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private EmbeddingProfileService profiles;
    @Autowired private PlatformTransactionManager transactionManager;
    // The FAQ answer path must not call Redis; no Redis server is started for these tests.
    @MockitoBean private StringRedisTemplate redis;

    private Long categoryId;
    private Long administratorId;
    private final FaqSearchResponseDto selected = new FaqSearchResponseDto(14L,
            "eSIM이 무엇인가요?", "공식 답변", .98, Intent.GENERAL, true, 1);

    @BeforeEach
    void prepareApprovedFaq() {
        jdbc.update("DELETE FROM faq_log");
        jdbc.update("DELETE FROM faq_embeddings");
        jdbc.update("DELETE FROM old_faq");
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM faq_category");
        profiles.getCurrentProfileId();
        categoryId = jdbc.queryForObject("INSERT INTO faq_category(name) VALUES ('공식 답변') RETURNING id", Long.class);
        LocalDateTime now = LocalDateTime.now();
        administratorId = users.saveAndFlush(User.builder().email("official-" + UUID.randomUUID() + "@test.com")
                .hashedPassword("test-only").name("관리자").role(UserRole.ADMIN)
                .createdAt(now).updatedAt(now).build()).getId();
        jdbc.update("""
                INSERT INTO faq(id,category_id,admin_id,question,answer,intent,cacheable)
                VALUES (14,?,?,'eSIM이 무엇인가요?','공식 답변','GENERAL',true)
                """, categoryId, administratorId);
        clearInvocations(redis);
    }

    @Test
    void approvedFaqReturnsItsVerifiedDbAnswerWithoutRedis() {
        assertThat(answers.getOfficialAnswer(selected)).contains("공식 답변");
        verifyNoInteractions(redis);
    }

    @Test
    void committedRevocationPreventsReuseOfTheSelectedAnswerWithoutRedis() {
        faqs.updateActiveFaq(request(Intent.GENERAL, false), administratorId);
        assertThat(jdbc.queryForObject("SELECT cacheable FROM faq WHERE id=14", Boolean.class)).isFalse();
        assertThat(answers.getOfficialAnswer(selected)).isEmpty();
        verifyNoInteractions(redis);
    }

    @Test
    void rollbackPreservesApprovalAndTheOfficialAnswer() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            faqs.updateActiveFaq(request(Intent.GENERAL, false), administratorId);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT version FROM faq WHERE id=14", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM old_faq WHERE faq_id=14", Integer.class)).isZero();
        assertThat(answers.getOfficialAnswer(selected)).contains("공식 답변");
        verifyNoInteractions(redis);
    }

    @Test
    void deletedFaqDoesNotReturnItsPreviousAnswer() {
        faqs.deleteFaq(14L);
        assertThat(answers.getOfficialAnswer(selected)).isEmpty();
        verifyNoInteractions(redis);
    }

    @Test
    void modifiedFaqDoesNotReturnThePreviouslySelectedSnapshot() {
        jdbc.update("UPDATE faq SET answer='수정된 답변',version=2 WHERE id=14");
        assertThat(answers.getOfficialAnswer(selected)).isEmpty();
        var latest = new FaqSearchResponseDto(14L, selected.question(), "수정된 답변", .98, Intent.GENERAL, true, 2);
        assertThat(answers.getOfficialAnswer(latest)).contains("수정된 답변");
        verifyNoInteractions(redis);
    }

    @Test
    void changingIntentRevokesApprovalAndPreventsDirectAnswers() {
        faqs.updateActiveFaq(request(Intent.USER_DATA, null), administratorId);
        assertThat(jdbc.queryForObject("SELECT cacheable FROM faq WHERE id=14", Boolean.class)).isFalse();
        assertThat(answers.getOfficialAnswer(selected)).isEmpty();
        verifyNoInteractions(redis);
    }

    private FaqUpdateRequestDto request(Intent intent, Boolean approval) {
        return new FaqUpdateRequestDto(14L, categoryId, selected.question(), selected.answer(), intent, approval);
    }
}
