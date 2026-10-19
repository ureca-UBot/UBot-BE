package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.pgvector.PGvector;
import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.service.AiService;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.context.GeneralIntentHandler;
import com.ubot.chat.context.StoreIntentHandler;
import com.ubot.chat.context.UserIntentHandler;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.cache.FaqCacheProperties;
import com.ubot.faq.cache.FaqSemanticCacheService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.FaqRetrievalProperties;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.faq.retrieval.rerank.FaqRerankService;
import com.ubot.faq.retrieval.rerank.RerankerClient;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.service.UnansweredQuestionService;
import com.ubot.user.dto.response.UserResponseDto;
import com.ubot.user.enums.UserRole;
import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import com.ubot.user.service.UserService;

@DisplayName("의도별 자료 수집의 혼합 요청과 설정 조합 검증")
class ChatAnswerProcessorIntentValidationTest {
    private static final String QUESTION = "일반 안내와 내 정보, 주변 매장을 알려줘";
    private static final PGvector QUERY_VECTOR = new PGvector(new float[] {1, 0});
    private static final LocalDateTime JOINED_AT = LocalDateTime.of(2026, 9, 10, 12, 0);
    private static final Location LOCATION = new Location(37.5, 127.0);

    private final FaqVectorService vectors = mock(FaqVectorService.class);
    private final EmbeddingService embeddings = mock(EmbeddingService.class);
    private final RerankerClient reranker = mock(RerankerClient.class);
    private final UserService users = mock(UserService.class);
    private final NearbyStoreSearcher stores = mock(NearbyStoreSearcher.class);
    private final AiService ai = mock(AiService.class);
    private final UnansweredQuestionService unanswered = mock(UnansweredQuestionService.class);

    @ParameterizedTest(name = "캐시={0}, 리랭커={1}, 위치={2}, 일반 문장 통과={3}")
    @CsvSource({
            "false, false, false, false", "false, false, false, true",
            "false, false, true, false", "false, false, true, true",
            "false, true, false, false", "false, true, false, true",
            "false, true, true, false", "false, true, true, true",
            "true, false, false, false", "true, false, false, true",
            "true, false, true, false", "true, false, true, true",
            "true, true, false, false", "true, true, false, true",
            "true, true, true, false", "true, true, true, true"
    })
    @DisplayName("세 의도가 섞여도 일반 문장만 비교하고 조회 자료와 후보 순서를 유지한다")
    void mixedIntentsPreserveDataAcrossConfigurations(
            boolean cacheEnabled, boolean rerankEnabled, boolean hasLocation, boolean generalPasses) {
        FaqSearchResponseDto general = faq(1L, Intent.GENERAL, 0.90, true);
        FaqSearchResponseDto user = faq(2L, Intent.USER_DATA, 0.89, false);
        FaqSearchResponseDto store = faq(3L, Intent.STORE_DATA, 0.88, false);
        List<FaqSearchResponseDto> candidates = List.of(general, user, store);
        int candidateK = rerankEnabled ? 10 : 3;
        when(vectors.searchWithVector(QUESTION, null, candidateK))
                .thenReturn(new FaqVectorService.SearchResult(candidates, QUERY_VECTOR));
        when(embeddings.embedTexts(List.of(general.answer())))
                .thenReturn(List.of(generalPasses ? QUERY_VECTOR : new PGvector(new float[] {0, 1})));
        if (rerankEnabled) {
            when(reranker.score(eq(QUESTION), anyList())).thenReturn(List.of(0.1, 0.2, 0.3));
        }
        existingUser();
        StoreMapResult map = new StoreMapResult(LOCATION, null, 3.0, List.of());
        if (hasLocation) {
            when(stores.search(LOCATION, null)).thenReturn(map);
            when(stores.format(map)).thenReturn("주변 매장이 없습니다.");
        }
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("수집 자료 기반 답변", null, false));

        ChatAnswerResult result = processor(cacheEnabled, rerankEnabled)
                .generateAnswer(attempt(null), hasLocation ? LOCATION : null, () -> { });

        assertThat(result.errorCode()).isNull();
        List<FaqSearchResponseDto> expectedOrder = rerankEnabled ? List.of(store, user, general) : candidates;
        assertThat(result.faqs()).containsExactlyElementsOf(expectedOrder.stream()
                .filter(faq -> faq.intent() != Intent.GENERAL || generalPasses).toList());
        AnswerMaterials materials = capturedMaterials();
        assertThat(materials.faqs()).containsExactlyElementsOf(generalPasses ? List.of(general) : List.of());
        assertThat(materials.sections()).extracting(ContextSection::content)
                .anySatisfy(content -> assertThat(content).contains("이름: 테스트회원", "가입일: 2026-09-10"));
        if (hasLocation) {
            assertThat(materials.storeMap()).isSameAs(map);
            assertThat(materials.sections()).hasSize(2);
            assertThat(materials.tools()).isEmpty();
            verify(stores).search(LOCATION, null);
        } else {
            assertThat(materials.storeMap()).isNull();
            assertThat(materials.sections()).hasSize(1);
            assertThat(materials.tools()).containsExactly(AiTool.STORE_SEARCH);
            verifyNoInteractions(stores);
        }
        verify(users).getActiveUser(1L);
        verify(embeddings).embedTexts(List.of(general.answer()));
        verifyNoMoreInteractions(embeddings);
        verify(vectors).searchWithVector(QUESTION, null, candidateK);
        verifyNoMoreInteractions(vectors);
        if (rerankEnabled) {
            verify(reranker).score(eq(QUESTION), anyList());
        } else {
            verifyNoInteractions(reranker);
        }
        verifyNoInteractions(unanswered);
    }

    @ParameterizedTest
    @EnumSource(value = Intent.class, names = {"USER_DATA", "STORE_DATA"})
    @DisplayName("조회용 FAQ는 cacheable이 true여도 공식 답변 캐시로 반환하지 않는다")
    void contextFaqIsNotServedAsOfficialCacheEvenWhenApproved(Intent intent) {
        FaqSearchResponseDto contextFaq = faq(10L, intent, 1.0, true);
        when(vectors.searchWithVector(QUESTION, null, 3)).thenReturn(new FaqVectorService.SearchResult(
                List.of(contextFaq, faq(11L, Intent.GENERAL, 0.6, false)), QUERY_VECTOR));
        if (intent == Intent.USER_DATA) {
            existingUser();
        }
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("실제 조회 경로 답변", null, false));

        ChatAnswerResult result = processor(true, false).generateAnswer(attempt(null), null, () -> { });

        assertThat(result.errorCode()).isNull();
        assertThat(result.answer().answer()).isEqualTo("실제 조회 경로 답변");
        assertThat(result.faqs()).containsExactly(contextFaq);
        AnswerMaterials materials = capturedMaterials();
        assertThat(materials.faqs()).isEmpty();
        if (intent == Intent.USER_DATA) {
            assertThat(materials.sections()).hasSize(1);
            verify(users).getActiveUser(1L);
        } else {
            assertThat(materials.tools()).containsExactly(AiTool.STORE_SEARCH);
            verifyNoInteractions(users);
        }
        verifyNoInteractions(embeddings, stores, reranker, unanswered);
    }

    @ParameterizedTest
    @EnumSource(value = Intent.class, names = {"USER_DATA", "STORE_DATA"})
    @DisplayName("의도 재검색도 문장 비교 없이 조회 자료를 수집하고 미응답을 중복 저장하지 않는다")
    void researchUsesSelectedIntentAndPreservesUnansweredPolicy(Intent intent) {
        FaqSearchResponseDto contextFaq = faq(10L, intent, 1.0, true);
        when(vectors.searchWithVector(QUESTION, intent, 3))
                .thenReturn(new FaqVectorService.SearchResult(List.of(contextFaq), QUERY_VECTOR));
        if (intent == Intent.USER_DATA) {
            existingUser();
        }
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("추가 확인이 필요합니다.", null, false, "ABSTAIN"));

        ChatAnswerResult result = processor(true, false).generateAnswer(attempt(intent), null, () -> { });

        assertThat(result.errorCode()).isNull();
        assertThat(result.answer().abstained()).isTrue();
        AnswerMaterials materials = capturedMaterials();
        assertThat(materials.faqs()).isEmpty();
        if (intent == Intent.USER_DATA) {
            assertThat(materials.sections()).hasSize(1);
        } else {
            assertThat(materials.tools()).containsExactly(AiTool.STORE_SEARCH);
        }
        verify(vectors).searchWithVector(QUESTION, intent, 3);
        verifyNoMoreInteractions(vectors);
        verifyNoInteractions(embeddings, stores, reranker, unanswered);
    }

    @Test
    @DisplayName("삭제됐거나 존재하지 않는 사용자는 실제 정보 대신 조회 불가 안내를 전달한다")
    void missingUserDoesNotBecomeInventedUserData() {
        FaqSearchResponseDto contextFaq = faq(10L, Intent.USER_DATA, 1.0, false);
        when(vectors.searchWithVector(QUESTION, null, 3))
                .thenReturn(new FaqVectorService.SearchResult(List.of(contextFaq), QUERY_VECTOR));
        when(users.getActiveUser(1L)).thenThrow(new UserException(UserErrorCode.USER_NOT_FOUND));
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("사용자 정보를 찾을 수 없습니다.", null, false, "ABSTAIN"));

        ChatAnswerResult result = processor(false, false).generateAnswer(attempt(null), null, () -> { });

        assertThat(result.errorCode()).isNull();
        assertThat(result.answer().abstained()).isTrue();
        assertThat(capturedMaterials().sections()).extracting(ContextSection::content)
                .containsExactly("사용자 정보를 찾을 수 없습니다.");
        verify(unanswered).createUnansweredQuestion(100L, QUESTION, UnansweredReason.LLM_ABSTAIN, 10L, 1.0);
        verifyNoInteractions(embeddings, stores, reranker);
    }

    private ChatAnswerProcessor processor(boolean cacheEnabled, boolean rerankEnabled) {
        FaqRetrievalProperties retrievalProperties = new FaqRetrievalProperties();
        retrievalProperties.getRerank().setEnabled(rerankEnabled);
        FaqCacheProperties cacheProperties = new FaqCacheProperties();
        cacheProperties.setEnabled(cacheEnabled);
        ChatContextCollector collector = new ChatContextCollector(List.of(
                new GeneralIntentHandler(), new UserIntentHandler(users), new StoreIntentHandler(stores)));
        ChatAnswerProcessor processor = new ChatAnswerProcessor(
                new FaqRetrievalService(vectors, new FaqRerankService(reranker), retrievalProperties, 3),
                ai, unanswered, collector, new FaqSemanticCacheService(cacheProperties),
                new FaqSentenceFilterService(embeddings, 0.6));
        ReflectionTestUtils.setField(processor, "topK", 3);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
        return processor;
    }

    private void existingUser() {
        when(users.getActiveUser(1L)).thenReturn(new UserResponseDto(
                1L, "test@example.com", "테스트회원", null, null, "서울", UserRole.USER, JOINED_AT));
    }

    private AnswerMaterials capturedMaterials() {
        ArgumentCaptor<AnswerMaterials> materials = ArgumentCaptor.captor();
        verify(ai).generateAnswer(materials.capture());
        return materials.getValue();
    }

    private AnswerAttemptsHistory attempt(Intent researchIntent) {
        return AnswerAttemptsHistory.builder().id(100L).userId(1L).question(QUESTION).attemptCount(1)
                .sourceAttemptId(researchIntent == null ? null : 90L).intent(researchIntent)
                .createdAt(JOINED_AT).status("PENDING").build();
    }

    private FaqSearchResponseDto faq(Long id, Intent intent, double score, boolean cacheable) {
        return new FaqSearchResponseDto(id, "FAQ 질문 " + id,
                intent == Intent.GENERAL ? "일반 답변." : "조회 안내.", score,
                intent, id + 100, "유사 질문 " + id, cacheable);
    }
}
