package com.ubot.faq.service;

import com.ubot.faq.enums.FaqVectorType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.config.CustomUserDetails;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.request.FaqCacheQuestionCreateRequestDto;
import com.ubot.faq.dto.response.FaqCacheQuestionResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCacheQuestion;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.repository.FaqVectorRepository;
import com.ubot.faq.repository.FaqCacheQuestionRepository;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FaqCacheQuestionIntegrationTest {
    private static final String ROUTE = "/admin/faqs/14/cache-questions";
    private static final String REQUEST = "{\"questions\":[\"esim이 뭐야?\",\"esim이 뭐야?\"]}";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FaqCacheQuestionService service;
    @Autowired private EmbeddingProfileService profiles;
    @Autowired private FaqVectorRepository vectors;
    @Autowired private FaqCacheQuestionRepository cacheQuestions;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private EmbeddingService embedding;

    private Long profileId;
    private Long categoryId;
    private User administrator;

    @BeforeEach
    void prepareFaqAndCurrentProfileVector() {
        jdbc.update("DELETE FROM faq_log");
        jdbc.update("DELETE FROM faq_embeddings");
        jdbc.update("DELETE FROM old_faq");
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM faq_category");
        categoryId = jdbc.queryForObject("INSERT INTO faq_category(name) VALUES ('표현 관리') RETURNING id", Long.class);
        profileId = profiles.getCurrentProfileId();
        jdbc.update("""
                INSERT INTO faq(id,category_id,question,answer,intent,cacheable)
                VALUES (14,?,'eSIM이 무엇인가요?','물리적인 SIM 카드 없이 사용하는 디지털 SIM입니다.','GENERAL',true)
                """, categoryId);
        vectors.saveVectorForFaq(14L, profileId, FaqVectorType.QUESTION, 1, vector(1));
        LocalDateTime now = LocalDateTime.now();
        administrator = users.saveAndFlush(User.builder().email("cache-" + UUID.randomUUID() + "@test.com")
                .hashedPassword("test-only").name("관리자").role(UserRole.ADMIN)
                .createdAt(now).updatedAt(now).build());
        when(embedding.embedText(anyString())).thenReturn(vector(1));
        when(embedding.embedTexts(anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<String> questions = call.getArgument(0);
            return Collections.nCopies(questions.size(), vector(0));
        });
    }

    @Test
    void administratorCanRegisterListDeactivateAndExplicitlyReregister() throws Exception {
        mvc.perform(post(ROUTE).with(administrator()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[0].valid").value(true));
        Long id = jdbc.queryForObject("SELECT id FROM faq_cache_question WHERE faq_id=14", Long.class);
        mvc.perform(post(ROUTE).with(administrator()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id.intValue()));
        assertThat(rowCount()).isEqualTo(1);
        assertThat(vectors.getSemanticCandidates(vector(0), profileId).getFirst().faq().similarityScore()).isEqualTo(1.0);

        mvc.perform(delete(ROUTE + "/" + id).with(administrator())).andExpect(status().isOk());
        mvc.perform(get(ROUTE).with(administrator())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].enabled").value(false))
                .andExpect(jsonPath("$.data[0].valid").value(false));
        assertThat(vectors.getSemanticCandidates(vector(0), profileId).getFirst().faq().similarityScore()).isZero();
        mvc.perform(delete(ROUTE + "/" + id).with(administrator())).andExpect(status().isOk());
        mvc.perform(post(ROUTE).with(administrator()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].enabled").value(true));
        assertThat(vectors.getSemanticCandidates(vector(0), profileId).getFirst().faq().similarityScore()).isEqualTo(1.0);
    }

    @Test
    void listingRetainsStaleAndOtherProfileRowsButMarksThemInvalid() {
        service.registerFaqCacheQuestions(14L, request("esim이 뭐야?"));
        jdbc.update("UPDATE faq SET answer='바뀐 답변', version=2 WHERE id=14");
        vectors.updateVectorVersionForFaq(14L, profileId, FaqVectorType.QUESTION, 2);
        assertThat(service.getFaqCacheQuestionList(14L).getFirst().valid()).isFalse();
        assertThat(service.getFaqCacheQuestionList(14L).getFirst().currentFaqVersion()).isEqualTo(2);
        service.registerFaqCacheQuestions(14L, request("esim이 뭐야?"));
        assertThat(service.getFaqCacheQuestionList(14L).getFirst().valid()).isTrue();
        Long other = jdbc.queryForObject("""
                INSERT INTO embedding_profiles(provider,model_name,dimensions,profile_version)
                VALUES ('openai-compatible','other-model',1024,1) RETURNING profile_id
                """, Long.class);
        jdbc.update("""
                INSERT INTO faq_cache_question(faq_id,profile_id,question,normalized_question,vector,faq_version,faq_question,faq_answer)
                SELECT id,?,'다른 모델 표현','다른 모델 표현',?,version,question,answer FROM faq WHERE id=14
                """, other, vector(0));
        assertThat(service.getFaqCacheQuestionList(14L)).hasSize(2);
        assertThat(service.getFaqCacheQuestionList(14L).get(1).valid()).isFalse();
    }

    @Test
    void revocationDuringEmbeddingRejectsTheEntireRegistration() {
        when(embedding.embedTexts(anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.update("UPDATE faq SET cacheable=false,version=version+1 WHERE id=14");
            return List.of(vector(0));
        });
        assertThatThrownBy(() -> service.registerFaqCacheQuestions(14L, request("새 표현")))
                .isInstanceOf(FaqException.class)
                .extracting(e -> ((FaqException) e).getErrorCode()).isEqualTo(FaqErrorCode.FAQ_CACHE_FAQ_CHANGED);
        assertThat(rowCount()).isZero();
    }

    @Test
    void faqEditDuringEmbeddingIsDetectedInTheAdministratorRequest() throws Exception {
        when(embedding.embedTexts(anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.update("UPDATE faq SET question='바뀐 공식 질문',version=version+1 WHERE id=14");
            return List.of(vector(0));
        });
        mvc.perform(post(ROUTE).with(administrator()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FAQ-009"));
        assertThat(rowCount()).isZero();
    }

    @Test
    void aDatabaseFailureAfterFirstInsertRollsBackAllExpressions() {
        when(embedding.embedTexts(anyList())).thenReturn(List.of(vector(0), new PGvector(new float[]{1, 0})));
        assertThatThrownBy(() -> service.registerFaqCacheQuestions(14L, request("첫 표현", "두 번째 표현")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rowCount()).isZero();
    }

    @Test
    void embeddingFailureHasNoWrites() {
        when(embedding.embedTexts(anyList())).thenThrow(new EmbeddingException(EmbeddingErrorCode.EMBEDDING_TIMEOUT));
        assertThatThrownBy(() -> service.registerFaqCacheQuestions(14L, request("표현")))
                .isInstanceOf(EmbeddingException.class);
        assertThat(rowCount()).isZero();
    }

    @Test
    void largerRegistrationsUseBoundedEmbeddingBatchesWithoutHoldingDatabaseTransactions() {
        when(embedding.embedTexts(anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<String> batch = call.getArgument(0);
            assertThat(batch).hasSizeLessThanOrEqualTo(8);
            return Collections.nCopies(batch.size(), vector(0));
        });
        List<String> questions = IntStream.range(0, 17).mapToObj(i -> "검토 표현 " + i).toList();
        assertThat(service.registerFaqCacheQuestions(14L, new FaqCacheQuestionCreateRequestDto(questions))).hasSize(17);
        assertThat(rowCount()).isEqualTo(17);
    }

    @Test
    void normalizedDuplicatesAcrossRequestsUseOneRowAndOneEmbeddingInput() {
        List<FaqCacheQuestionResponseDto> first = service.registerFaqCacheQuestions(14L, request("  esim이  뭐야？？？ ", "esim이 뭐야?", "esim이\t뭐야??"));
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().question()).isEqualTo("  esim이  뭐야？？？ ");
        assertThat(first.getFirst().normalizedQuestion()).isEqualTo("esim이 뭐야?");
        assertThat(first.getFirst().normalizationVersion()).isEqualTo(1);
        verify(embedding).embedTexts(List.of("esim이 뭐야?"));
        List<FaqCacheQuestionResponseDto> second = service.registerFaqCacheQuestions(14L, request("esim이 뭐야????"));
        assertThat(second.getFirst().id()).isEqualTo(first.getFirst().id());
        assertThat(second.getFirst().createdAt()).isEqualTo(first.getFirst().createdAt());
        assertThat(second.getFirst().question()).isEqualTo("esim이 뭐야????");
        assertThat(jdbc.queryForObject("SELECT question FROM faq_cache_question WHERE id=?",
                String.class, first.getFirst().id())).isEqualTo("esim이 뭐야????");
        assertThat(rowCount()).isEqualTo(1);
        service.deactivateFaqCacheQuestion(14L, first.getFirst().id());
        assertThat(service.getFaqCacheQuestionList(14L).getFirst().valid()).isFalse();
        List<FaqCacheQuestionResponseDto> reactivated = service.registerFaqCacheQuestions(14L, request("esim이 뭐야？"));
        assertThat(reactivated.getFirst().id()).isEqualTo(first.getFirst().id());
        assertThat(reactivated.getFirst().enabled()).isTrue();
        assertThat(reactivated.getFirst().valid()).isTrue();
    }

    @Test
    void unversionedImportsCannotBecomeCacheCandidatesBeforeJavaRegistration() {
        jdbc.update("""
                INSERT INTO faq_cache_question(faq_id,profile_id,question,normalized_question,vector,faq_version,faq_question,faq_answer)
                SELECT id,?,'esim이 뭐야??','esim이 뭐야?',?,version,question,answer FROM faq WHERE id=14
                """, profileId, vector(0));
        FaqCacheQuestionResponseDto old = service.getFaqCacheQuestionList(14L).getFirst();
        assertThat(old.normalizationVersion()).isZero();
        assertThat(old.valid()).isFalse();
        assertThat(vectors.getSemanticCandidates(vector(0),profileId).getFirst().faq().similarityScore()).isZero();
        List<FaqCacheQuestionResponseDto> refreshed = service.registerFaqCacheQuestions(14L,request("esim이 뭐야?"));
        assertThat(refreshed.getFirst().id()).isEqualTo(old.id());
        assertThat(refreshed.getFirst().valid()).isTrue();
        assertThat(vectors.getSemanticCandidates(vector(0),profileId).getFirst().faq().similarityScore()).isEqualTo(1.0);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void entityNormalizesInJavaAndMapsVectorAndDatabaseTimestamp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            FaqCacheQuestion entity = FaqCacheQuestion.builder().faq(entityManager.getReference(Faq.class,14L))
                    .profileId(profileId).question("  esim이  뭐야？？？ ").normalizationVersion(1)
                    .vector(vector(0).toArray()).faqVersion(1).faqQuestion("eSIM이 무엇인가요?")
                    .faqAnswer("물리적인 SIM 카드 없이 사용하는 디지털 SIM입니다.").build();
            entityManager.persist(entity);
            entityManager.flush();
            Long id = entity.getId();
            assertThat(entity.getNormalizedQuestion()).isEqualTo("esim이 뭐야?");
            assertThat(entity.getCreatedAt()).isNotNull();
            assertThat(jdbc.queryForObject("SELECT normalized_question FROM faq_cache_question WHERE id=?",
                    String.class, id)).isEqualTo(entity.getNormalizedQuestion());
            entityManager.clear();
            FaqCacheQuestion loaded = entityManager.find(FaqCacheQuestion.class,id);
            assertThat(loaded.getFaq().getId()).isEqualTo(14L);
            assertThat(loaded.getProfileId()).isEqualTo(profileId);
            assertThat(loaded.getVector()).containsExactly(vector(0).toArray());
            assertThat(loaded.getNormalizationVersion()).isEqualTo(1);
            assertThat(cacheQuestions.findByIdAndFaq_Id(id,15L)).isEmpty();
            cacheQuestions.findByIdAndFaq_Id(id,14L).orElseThrow().deactivate();
            cacheQuestions.flush();
            entityManager.clear();
            assertThat(entityManager.find(FaqCacheQuestion.class,id).isEnabled()).isFalse();
        });
    }

    @Test
    void concurrentNormalizedRegistrationsKeepOneRowWithTheEmbeddedInput() throws Exception {
        CountDownLatch bothEmbedding = new CountDownLatch(2);
        when(embedding.embedTexts(anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<String> inputs = call.getArgument(0);
            assertThat(inputs).containsExactly("esim이 뭐야?");
            bothEmbedding.countDown();
            assertThat(bothEmbedding.await(10, TimeUnit.SECONDS)).isTrue();
            return List.of(vector(0));
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<FaqCacheQuestionResponseDto>> first = executor.submit(() -> service.registerFaqCacheQuestions(14L, request("  esim이  뭐야？？？ ")));
            Future<List<FaqCacheQuestionResponseDto>> second = executor.submit(() -> service.registerFaqCacheQuestions(14L, request("esim이\t뭐야??")));
            FaqCacheQuestionResponseDto firstResult = first.get(20, TimeUnit.SECONDS).getFirst();
            FaqCacheQuestionResponseDto secondResult = second.get(20, TimeUnit.SECONDS).getFirst();
            assertThat(firstResult.id()).isEqualTo(secondResult.id());
            assertThat(rowCount()).isEqualTo(1);
            assertThat(service.getFaqCacheQuestionList(14L).getFirst().normalizedQuestion()).isEqualTo("esim이 뭐야?");
            assertThat(jdbc.queryForObject("SELECT vector = ? FROM faq_cache_question WHERE id=?",
                    Boolean.class, vector(0), firstResult.id())).isTrue();
        }
    }

    @Test
    void unicodeSpacingOnlyExpressionsAreRejectedBeforeEmbedding() throws Exception {
        mvc.perform(post(ROUTE).with(administrator()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"questions\":[\"\u00A0\u3000\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FAQ-012"));
        verify(embedding,never()).embedTexts(anyList());
        assertThat(rowCount()).isZero();
    }

    @Test
    void approvalAndCurrentCanonicalVectorAreRequiredBeforeEmbedding() throws Exception {
        jdbc.update("UPDATE faq SET cacheable=false WHERE id=14");
        mvc.perform(post(ROUTE).with(administrator()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FAQ-008"));
        jdbc.update("UPDATE faq SET cacheable=true,intent='USER_DATA' WHERE id=14");
        mvc.perform(post(ROUTE).with(administrator()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FAQ-008"));
        jdbc.update("UPDATE faq SET cacheable=true,intent='GENERAL' WHERE id=14");
        jdbc.update("DELETE FROM faq_embeddings WHERE faq_id=14");
        mvc.perform(post(ROUTE).with(administrator()).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FAQ-010"));
        verify(embedding, never()).embedTexts(anyList());
    }

    @Test
    void malformedRequestsAndNonAdministratorCallsCannotRegister() throws Exception {
        mvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROUTE)).andExpect(status().isUnauthorized());
        mvc.perform(delete(ROUTE + "/1")).andExpect(status().isUnauthorized());
        mvc.perform(post(ROUTE).with(user("member").roles("USER"))
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isForbidden());
        mvc.perform(get(ROUTE).with(user("member").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(delete(ROUTE + "/1").with(user("member").roles("USER"))).andExpect(status().isForbidden());
        for (String body : List.of("{}", "{\"questions\":[]}", "{\"questions\":[null]}", "{\"questions\":[\" \" ]}",
                "{\"questions\":[" + String.join(",", Collections.nCopies(51, "\"표현\"")) + "]}",
                "{\"questions\":[\"" + "가".repeat(161) + "\"]}")) {
            mvc.perform(post(ROUTE).with(administrator()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(embedding, never()).embedTexts(anyList());
    }

    @Test
    void wrongFaqInDeactivationCannotModifyAnotherFaqExpression() throws Exception {
        FaqCacheQuestionResponseDto row = service.registerFaqCacheQuestions(14L, request("표현")).getFirst();
        jdbc.update("""
                INSERT INTO faq(id,category_id,question,answer,intent) VALUES (15,?,'다른 질문','다른 답변','GENERAL')
                """, categoryId);
        mvc.perform(delete("/admin/faqs/15/cache-questions/" + row.id()).with(administrator()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FAQ-011"));
        assertThat(service.getFaqCacheQuestionList(14L).getFirst().enabled()).isTrue();
    }

    @Test
    void existingFaqCreateJsonWithoutCacheableStillWorks() throws Exception {
        String body = """
                {"categoryId":%d,"question":"기존 프론트 요청","answer":"답변","intent":"GENERAL"}
                """.formatted(categoryId);
        mvc.perform(post("/admin/faqs").with(administrator()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.cacheable").value(false));
    }

    private RequestPostProcessor administrator() {
        return user(new CustomUserDetails(administrator));
    }

    private FaqCacheQuestionCreateRequestDto request(String... questions) {
        return new FaqCacheQuestionCreateRequestDto(List.of(questions));
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM faq_cache_question WHERE faq_id=14", Integer.class);
    }

    private PGvector vector(int index) {
        float[] values = new float[1024];
        values[index] = 1;
        return new PGvector(values);
    }
}
