package com.ubot.chat.controller;

import com.ubot.ai.service.AiGuardModelService;
import com.ubot.ai.service.AiGuardTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.RedisTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.chat.job.support.ChatJobTestDriver;
import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.faq.cache.FaqCacheProperties;
import com.ubot.faq.enums.FaqVectorType;
import com.ubot.faq.repository.FaqVectorRepository;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;

/** 실제 인증·검색 SQL·판정·비동기 답변 저장을 연결합니다. 외부 임베딩/LLM 통신만 모의 객체입니다. */
@SpringBootTest(properties = {
        "faq.cache.enabled=true",
        "CHAT_TOP_K=3", "CHAT_CONFIDENCE_THRESHOLD=0.75"})
@Import({ChatJobTestDriver.Config.class, PgvectorTestConfiguration.class, RedisTestConfiguration.class})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("공식 FAQ 캐시 HTTP 통합 테스트")
class ChatSemanticCacheIntegrationTest {
    @MockitoBean AiGuardModelService guardModel;

    private static final String QUESTION = "이심이 어떤 건지 알려줘";
    private static final String OFFICIAL_ANSWER = "물리적인 SIM 카드 없이 사용하는 디지털 SIM입니다.\n";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private JwtUtil jwt;
    @Autowired private EmbeddingProfileService profiles;
    @Autowired private FaqVectorRepository vectors;
    @Autowired private FaqCacheProperties cacheProperties;
    @Autowired private ChatJobTestDriver driver;
    @Autowired private StringRedisTemplate redis;
    @MockitoBean private EmbeddingClient embeddingClient;
    @MockitoBean private LlmClient llm;

    private String authorization;
    private Long faqId;
    private ChatJobTestFlow flow;

    @BeforeEach
    void prepareData() {
        AiGuardTestSupport.allow(guardModel);
        cacheProperties.setEnabled(true);
        // 공유 Redis라 이전 테스트 작업이 섞이지 않게 비움
        redis.delete(ChatJobStream.STREAM_KEY);
        redis.delete(redis.keys("chat:jobs:result:*"));
        redis.delete(redis.keys("chat:jobs:published:*"));
        flow = new ChatJobTestFlow(mvc, driver);
        // 테스트별 활성 후보만 남깁니다. 이 DB는 Testcontainers 전용이며 개발 DB와 분리되어 있습니다.
        jdbc.update("UPDATE faq SET deleted_at = now()");
        User user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
                .hashedPassword("test-hash").name("캐시 테스트").role(UserRole.USER)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        authorization = "Bearer " + jwt.createAccessToken(user);
        when(embeddingClient.getModelName()).thenReturn("cache-integration-embedding");
        when(embeddingClient.embed(QUESTION)).thenReturn(vector(0));
        when(embeddingClient.embedBatch(any())).thenAnswer(invocation -> {
            java.util.List<String> texts = invocation.getArgument(0);
            return texts.stream().map(text -> vector(0)).toList();
        });
        Long profileId = profiles.getCurrentProfileId();
        Long categoryId = jdbc.queryForObject("INSERT INTO faq_category(name) VALUES (?) RETURNING id",
                Long.class, UUID.randomUUID().toString());
        faqId = insertFaq(categoryId, "eSIM이 무엇인가요?", OFFICIAL_ANSWER, true);
        Long variantId = insertVariant(faqId, "이심의 의미를 설명해줘");
        vectors.saveVectorForVariant(variantId, profileId, FaqVectorType.QUESTION, 1, vector(0));
        Long competitorId = insertFaq(categoryId, "eSIM 발급 방법은 무엇인가요?", "발급 안내", false);
        Long competitorVariantId = insertVariant(competitorId, "이심 발급 방법");
        vectors.saveVectorForVariant(competitorVariantId, profileId, FaqVectorType.QUESTION, 1, vector(1));
    }

    @Test
    @DisplayName("HIT은 공식 답변 그대로 HTTP 성공·답변 이력·근거 FAQ 한 건을 저장하고 LLM을 호출하지 않는다")
    void hitPersistsOfficialAnswerThroughExistingChatFlow() throws Exception {
        askQuestion()
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.answer").value(OFFICIAL_ANSWER));

        assertThat(jdbc.queryForObject("SELECT llm_question FROM question_log ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo(OFFICIAL_ANSWER);
        Long questionLogId = jdbc.queryForObject("SELECT id FROM question_log ORDER BY id DESC LIMIT 1", Long.class);
        assertThat(jdbc.queryForList("SELECT faq_id FROM faq_log WHERE question_log_id = ? ORDER BY rank",
                Long.class, questionLogId)).containsExactly(faqId);
        assertThat(jdbc.queryForObject("SELECT status FROM answer_attempts_history ORDER BY attempt_id DESC LIMIT 1",
                String.class)).isEqualTo("SUCCESS");
        verify(embeddingClient).embed(QUESTION);
        verifyNoInteractions(llm, guardModel);
    }

    @Test
    @DisplayName("캐시 OFF는 같은 검색 데이터로 기존 LLM 답변을 반환한다")
    void cacheOffCallsLlm() throws Exception {
        cacheProperties.setEnabled(false);
        when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("LLM 생성 답변"));
        askQuestion()
                .andExpect(jsonPath("$.data.answer").value("LLM 생성 답변"));
        verify(embeddingClient).embed(QUESTION);
        verify(llm).generateAnswer(any());
        verify(guardModel).generate(argThat(request -> request != null && request.generationOptions() != null
                && "whole_answer_guard".equals(request.generationOptions().schemaName())));
    }

    @Test
    @DisplayName("관리자가 캐시 승인을 해제한 FAQ는 검색 점수가 1이어도 LLM으로 처리한다")
    void unapprovedFaqCallsLlm() throws Exception {
        jdbc.update("UPDATE faq SET cacheable = false WHERE id = ?", faqId);
        when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("승인 해제 후 LLM 답변"));
        askQuestion()
                .andExpect(jsonPath("$.data.answer").value("승인 해제 후 LLM 답변"));
        verify(embeddingClient).embed(QUESTION);
        verify(llm).generateAnswer(any());
        verify(guardModel).generate(argThat(request -> request != null && request.generationOptions() != null
                && "whole_answer_guard".equals(request.generationOptions().schemaName())));
    }

    // 접수(202) → Worker 처리 → 상태 조회. 성공한 조회 응답을 돌려줌
    private ResultActions askQuestion() throws Exception {
        var submitted = flow.submit(post("/chat/questions").header("Authorization", authorization)
                .contentType("application/json").content("{\"question\":\"" + QUESTION + "\"}"));
        driver.processAvailableJobs();
        return mvc.perform(flow.statusOf(submitted)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    private Long insertFaq(Long categoryId, String question, String answer, boolean cacheable) {
        return jdbc.queryForObject("""
                INSERT INTO faq(category_id, question, answer, intent, cacheable)
                VALUES (?, ?, ?, 'GENERAL', ?) RETURNING id
                """, Long.class, categoryId, question, answer, cacheable);
    }

    private Long insertVariant(Long originFaqId, String question) {
        return jdbc.queryForObject("INSERT INTO faq_question_variants(origin_faq_id, question) VALUES (?, ?) RETURNING id",
                Long.class, originFaqId, question);
    }

    private PGvector vector(int axis) {
        float[] values = new float[1024];
        values[axis] = 1;
        return new PGvector(values);
    }
}
