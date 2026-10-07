package com.ubot.chat.controller;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.chat.repository.QuestionLogRepository;
import com.ubot.common.ErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqLogRepository;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.unanswered.repository.UnansweredQuestionGroupRepository;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "prompt.faq.system-location=classpath:prompts/test-faq-system.txt",
        "prompt.faq.user-location=classpath:prompts/test-faq-user.txt",
        "CHAT_TOP_K=3",
        "CHAT_CONFIDENCE_THRESHOLD=0.75",
        "CHAT_MAX_ATTEMPTS=3" })
@Import(PgvectorTestConfiguration.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("의도 재검색 통합 테스트")
class ChatResearchIntegrationTest {
    private static final String QUESTION = "유심 재발급 방법";

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtUtil jwt;
    @Autowired
    UserRepository users;
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
    JdbcTemplate jdbc;
    @MockitoBean
    FaqVectorService vector;
    @MockitoBean
    LlmClient llm;

    User user;
    Long faqId;

    @BeforeEach
    void setup() {
        unansweredQuestions.deleteAllInBatch();
        unansweredQuestionGroups.deleteAllInBatch();
        faqLogs.deleteAllInBatch();
        questions.deleteAllInBatch();
        attempts.deleteAllInBatch();
        jdbc.update("delete from conversations");
        user = saveUser();
        Long category = jdbc.queryForObject(
                "insert into faq_category(name) values (?) returning id", Long.class, UUID.randomUUID().toString());
        faqId = jdbc.queryForObject("insert into faq(category_id,question,answer) values (?, ?, ?) returning id",
                Long.class, category, "유심 재발급", "매장 방문");
        var faq = new FaqSearchResponseDto(faqId, "q", "a", 0.9, Intent.GENERAL);
        when(vector.getSimilarList(anyString(), eq(3))).thenReturn(List.of(faq));
        when(vector.getSimilarListByIntent(anyString(), any(Intent.class), eq(3))).thenReturn(List.of(faq));
        when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("원본 답변"));
    }

    @Test
    @DisplayName("성공한 답변을 다른 의도로 재검색하면 원본과 독립된 새 시도로 답변을 만든다")
    void researchCreatesIndependentAttempt() throws Exception {
        String originalKey = keyFrom(complete(memberQuestion(), null));
        doReturn(new LlmResponseDto("재검색 답변")).when(llm).generateAnswer(any());

        String body = complete(memberResearch(originalKey, "USER_DATA"), null);

        assertThat(body).contains("재검색 답변", "\"status\":\"SUCCESS\"", "\"attemptCount\":1");
        String researchKey = keyFrom(body);
        assertThat(researchKey).isNotEqualTo(originalKey);
        AnswerAttemptsHistory original = latest(originalKey);
        AnswerAttemptsHistory research = latest(researchKey);
        assertThat(original.getStatus()).isEqualTo("SUCCESS");
        assertThat(research.getSourceAttemptId()).isEqualTo(original.getId());
        assertThat(research.getIntent()).isEqualTo(Intent.USER_DATA);
        assertThat(research.getStatus()).isEqualTo("SUCCESS");
        assertThat(research.getQuestion()).isEqualTo(QUESTION);
    }

    @Test
    @DisplayName("같은 의도로 다시 재검색하면 CHAT-019로 거절하고, 다른 의도는 허용한다")
    void sameIntentIsRejectedAndOtherIntentIsAllowed() throws Exception {
        String originalKey = keyFrom(complete(memberQuestion(), null));
        complete(memberResearch(originalKey, "USER_DATA"), null);
        long before = attempts.count();

        mvc.perform(memberResearch(originalKey, "USER_DATA"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ChatErrorCode.ALREADY_RESEARCHED.getCode()));
        assertThat(attempts.count()).isEqualTo(before);

        complete(memberResearch(originalKey, "STORE_DATA"), null);
        assertThat(attempts.count()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("재검색이 FAQ 부족으로 실패해도 미응답 질문을 저장하지 않고, 재시도할 수 없다")
    void failedResearchDoesNotCreateUnansweredQuestionAndCannotBeRetried() throws Exception {
        String originalKey = keyFrom(complete(memberQuestion(), null));
        when(vector.getSimilarListByIntent(anyString(), eq(Intent.STORE_DATA), eq(3))).thenReturn(List.of());

        String body = complete(memberResearch(originalKey, "STORE_DATA"), ChatErrorCode.NO_FAQ);

        assertThat(body).contains("\"status\":\"FAIL\"", "\"retryable\":false");
        assertThat(unansweredQuestions.count()).isZero();
        String researchKey = keyFrom(body);
        assertThat(latest(researchKey).getSourceAttemptId()).isNotNull();

        mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", researchKey)
                .header("Authorization", bearer(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ChatErrorCode.RETRY_NOT_ALLOWED.getCode()));
    }

    @Test
    @DisplayName("비로그인 요청은 재검색할 수 없다")
    void guestCannotResearch() throws Exception {
        mvc.perform(research("a".repeat(64), "GENERAL"))
                .andExpect(status().isUnauthorized());
        assertThat(attempts.count()).isZero();
    }

    @Test
    @DisplayName("실패한 답변과 재검색 시도는 다시 재검색할 수 없다")
    void failedOriginalAndResearchAttemptAreRejected() throws Exception {
        doThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT)).when(llm).generateAnswer(any());
        String failedKey = keyFrom(complete(memberQuestion(), LlmErrorCode.LLM_TIMEOUT));
        mvc.perform(memberResearch(failedKey, "GENERAL"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ChatErrorCode.RESEARCH_NOT_ALLOWED.getCode()));

        doReturn(new LlmResponseDto("원본 답변")).when(llm).generateAnswer(any());
        String originalKey = keyFrom(complete(memberQuestion(), null));
        String researchKey = keyFrom(complete(memberResearch(originalKey, "GENERAL"), null));
        mvc.perform(memberResearch(researchKey, "USER_DATA"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ChatErrorCode.RESEARCH_NOT_ALLOWED.getCode()));
    }

    @Test
    @DisplayName("다른 사용자의 키나 없는 키로는 재검색할 수 없다")
    void otherUsersKeyAndUnknownKeyAreNotFound() throws Exception {
        String originalKey = keyFrom(complete(memberQuestion(), null));
        User other = saveUser();

        mvc.perform(research(originalKey, "GENERAL").header("Authorization", bearer(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ChatErrorCode.ATTEMPT_NOT_FOUND.getCode()));
        mvc.perform(memberResearch("b".repeat(64), "GENERAL"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ChatErrorCode.ATTEMPT_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("키 형식이 틀리거나 의도가 잘못되면 거절한다")
    void invalidRequestIsRejected() throws Exception {
        mvc.perform(memberResearch("not-a-key", "GENERAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ChatErrorCode.INVALID_CHAT_REQUEST.getCode()));
        mvc.perform(memberResearch("a".repeat(64), "FOO"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/chat/questions/research").header("Idempotency-Key", "a".repeat(64))
                .header("Authorization", bearer(user)).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(attempts.count()).isZero();
    }

    @Test
    @DisplayName("게스트로 질문한 뒤 로그인해 첫 요청으로 재검색해도 대화를 승계해 재검색한다")
    void guestConversationIsClaimedBeforeResearch() throws Exception {
        var session = new MockHttpSession();
        String guestKey = keyFrom(complete(guestQuestion().session(session), null));

        String body = complete(research(guestKey, "GENERAL").session(session)
                .header("Authorization", bearer(user)), null);

        assertThat(body).contains("\"status\":\"SUCCESS\"");
        assertThat(attempts.findAll()).hasSize(2)
                .allSatisfy(attempt -> assertThat(attempt.getUserId()).isEqualTo(user.getId()));
        assertThat(session.isInvalid()).isTrue();
    }

    private MockHttpServletRequestBuilder guestQuestion() {
        return post("/chat/questions").contentType("application/json")
                .content("{\"question\":\"" + QUESTION + "\"}");
    }

    private MockHttpServletRequestBuilder memberQuestion() {
        return guestQuestion().header("Authorization", bearer(user));
    }

    private MockHttpServletRequestBuilder research(String idempotencyKey, String intent) {
        return post("/chat/questions/research").header("Idempotency-Key", idempotencyKey)
                .contentType("application/json").content("{\"intent\":\"" + intent + "\"}");
    }

    private MockHttpServletRequestBuilder memberResearch(String idempotencyKey, String intent) {
        return research(idempotencyKey, intent).header("Authorization", bearer(user));
    }

    private String complete(MockHttpServletRequestBuilder request, ErrorCode expectedError) throws Exception {
        var pending = mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(pending))
                .andExpect(status().is(expectedError == null ? 200 : expectedError.getStatus().value()))
                .andExpect(jsonPath("$.code").value(expectedError == null ? "SUCCESS" : expectedError.getCode()))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String keyFrom(String body) {
        var matcher = Pattern.compile("\"idempotencyKey\":\"([0-9a-f]{64})\"").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private AnswerAttemptsHistory latest(String idempotencyKey) {
        return attempts.findFirstByUserIdAndIdempotencyKeyOrderByAttemptCountDesc(user.getId(), idempotencyKey)
                .orElseThrow();
    }

    private String bearer(User target) {
        return "Bearer " + jwt.createAccessToken(target);
    }

    private User saveUser() {
        return users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
                .hashedPassword("test-hash").name("재검색 통합 테스트").role(UserRole.USER)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }
}