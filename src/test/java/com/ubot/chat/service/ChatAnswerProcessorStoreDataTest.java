package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.DenseFaqCandidates;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.faq.retrieval.RetrievalOutcome;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.service.UnansweredQuestionService;

@DisplayName("매장 조회 FAQ의 문장 필터 분리")
class ChatAnswerProcessorStoreDataTest {
    private static final String QUESTION = "가까운 매장을 알려줘";
    private static final PGvector QUERY_VECTOR = new PGvector(new float[] {1, 0});
    private static final Location LOCATION = new Location(37.5, 127.0);

    private final FaqRetrievalService retrieval = mock(FaqRetrievalService.class);
    private final FaqSentenceFilterService sentenceFilter = mock(FaqSentenceFilterService.class);
    private final NearbyStoreSearcher stores = mock(NearbyStoreSearcher.class);
    private final AiService ai = mock(AiService.class);
    private final UnansweredQuestionService unanswered = mock(UnansweredQuestionService.class);

    @Test
    @DisplayName("위치가 없으면 안내 문장을 비교하지 않고 매장 검색 도구를 제공한다")
    void storeFaqWithoutLocationEnablesSearchTool() {
        FaqSearchResponseDto storeFaq = faq(10L, Intent.STORE_DATA, 0.9);
        search(List.of(storeFaq));
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("매장 안내", null, false));

        ChatAnswerResult result = processor().generateAnswer(attempt(), null, () -> { });

        assertThat(result.errorCode()).isNull();
        assertThat(result.faqs()).containsExactly(storeFaq);
        AnswerMaterials materials = capturedMaterials();
        assertThat(materials.question()).isEqualTo(QUESTION);
        assertThat(materials.tools()).containsExactly(AiTool.STORE_SEARCH);
        assertThat(materials.faqs()).isEmpty();
        assertThat(materials.sections()).isEmpty();
        assertThat(materials.storeMap()).isNull();
        verifyNoInteractions(sentenceFilter, stores, unanswered);
    }

    @Test
    @DisplayName("위치가 있으면 전달된 좌표로 조회한 매장 자료와 지도를 사용한다")
    void storeFaqWithLocationCollectsStoreDataAndMap() {
        FaqSearchResponseDto storeFaq = faq(10L, Intent.STORE_DATA, 1.0);
        NearbyStoreResponseDto store = new NearbyStoreResponseDto(
                20L, "테스트 매장", "서울", "강남구", "테스트 주소", "02-000-0000", "10:00~18:00",
                37.501, 127.001, 0.2);
        StoreMapResult map = new StoreMapResult(LOCATION, null, 3.0, List.of(store));
        search(List.of(storeFaq));
        when(stores.search(LOCATION, null)).thenReturn(map);
        when(stores.format(map)).thenReturn("테스트 매장: 현재 위치에서 0.2km");
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("매장 안내", map, false));

        ChatAnswerResult result = processor().generateAnswer(attempt(), LOCATION, () -> { });

        assertThat(result.errorCode()).isNull();
        assertThat(result.faqs()).containsExactly(storeFaq);
        assertThat(result.answer().storeMap()).isSameAs(map);
        AnswerMaterials materials = capturedMaterials();
        assertThat(materials.storeMap()).isSameAs(map);
        assertThat(materials.sections()).extracting(ContextSection::content)
                .containsExactly("테스트 매장: 현재 위치에서 0.2km");
        assertThat(materials.faqs()).isEmpty();
        assertThat(materials.tools()).isEmpty();
        verify(stores).search(LOCATION, null);
        verifyNoInteractions(sentenceFilter, unanswered);
    }

    @Test
    @DisplayName("주변 매장이 없으면 빈 조회 결과와 지도를 그대로 전달한다")
    void emptyNearbyResultRemainsAvailableToAnswerAndMap() {
        StoreMapResult map = new StoreMapResult(LOCATION, null, 3.0, List.of());
        search(List.of(faq(10L, Intent.STORE_DATA, 0.9)));
        when(stores.search(LOCATION, null)).thenReturn(map);
        when(stores.format(map)).thenReturn("반경 3km 안에 매장이 없습니다.");
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("주변 매장이 없습니다.", map, false));

        ChatAnswerResult result = processor().generateAnswer(attempt(), LOCATION, () -> { });

        assertThat(result.errorCode()).isNull();
        AnswerMaterials materials = capturedMaterials();
        assertThat(materials.storeMap()).isSameAs(map);
        assertThat(materials.storeMap().stores()).isEmpty();
        assertThat(materials.sections()).extracting(ContextSection::content)
                .containsExactly("반경 3km 안에 매장이 없습니다.");
        verifyNoInteractions(sentenceFilter, unanswered);
    }

    @Test
    @DisplayName("매장 조회 실패 안내와 LLM 미응답 이력 처리를 유지한다")
    void lookupFailureProvidesFailureContextAndPreservesAbstention() {
        search(List.of(faq(10L, Intent.STORE_DATA, 0.9)));
        when(stores.search(LOCATION, null)).thenThrow(new IllegalStateException("test lookup failure"));
        when(ai.generateAnswer(any()))
                .thenReturn(new AiAnswer("매장 정보를 조회하지 못했습니다.", null, false, "ABSTAIN"));

        ChatAnswerResult result = processor().generateAnswer(attempt(), LOCATION, () -> { });

        assertThat(result.errorCode()).isNull();
        assertThat(result.answer().abstained()).isTrue();
        AnswerMaterials materials = capturedMaterials();
        assertThat(materials.storeMap()).isNull();
        assertThat(materials.sections()).singleElement().satisfies(section ->
                assertThat(section.content()).contains("매장 정보를 조회하지 못했습니다.", "매장 이름이나 주소를 추측하지 말고"));
        verify(unanswered).createUnansweredQuestion(100L, QUESTION, UnansweredReason.LLM_ABSTAIN, 10L, 0.9);
        verifyNoInteractions(sentenceFilter);
    }

    @Test
    @DisplayName("질문 유사도가 기준 미달이면 위치가 있어도 매장을 조회하지 않는다")
    void lowQuestionSimilarityDoesNotTriggerStoreLookup() {
        search(List.of(faq(10L, Intent.STORE_DATA, 0.74)));

        ChatAnswerResult result = processor().generateAnswer(attempt(), LOCATION, () -> { });

        assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
        verifyNoInteractions(stores, sentenceFilter, ai);
        verify(unanswered).createUnansweredQuestion(100L, QUESTION, UnansweredReason.INSUFFICIENT_FAQ, 10L, 0.74);
    }

    @Test
    @DisplayName("매장 FAQ가 여러 개 검색돼도 같은 요청의 주변 매장 조회는 한 번만 수행한다")
    void multipleStoreFaqsPerformOneLookup() {
        List<FaqSearchResponseDto> candidates = List.of(
                faq(10L, Intent.STORE_DATA, 0.95), faq(11L, Intent.STORE_DATA, 0.85));
        StoreMapResult map = new StoreMapResult(LOCATION, null, 3.0, List.of());
        search(candidates);
        when(stores.search(LOCATION, null)).thenReturn(map);
        when(stores.format(map)).thenReturn("주변 매장이 없습니다.");
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("주변 매장이 없습니다.", map, false));

        ChatAnswerResult result = processor().generateAnswer(attempt(), LOCATION, () -> { });

        assertThat(result.faqs()).containsExactlyElementsOf(candidates);
        assertThat(capturedMaterials().sections()).hasSize(1);
        verify(stores).search(LOCATION, null);
        verifyNoInteractions(sentenceFilter);
    }

    @Test
    @DisplayName("일반 FAQ 문장이 모두 탈락해도 매장 검색 도구를 제공한다")
    void storeLookupContinuesWhenGeneralSentencesAreRejected() {
        FaqSearchResponseDto generalFaq = faq(1L, Intent.GENERAL, 0.95);
        FaqSearchResponseDto storeFaq = faq(10L, Intent.STORE_DATA, 0.9);
        search(List.of(generalFaq, storeFaq));
        when(sentenceFilter.filter(QUERY_VECTOR, List.of(generalFaq))).thenReturn(List.of());
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("매장 안내", null, false));

        ChatAnswerResult result = processor().generateAnswer(attempt(), null, () -> { });

        assertThat(result.errorCode()).isNull();
        assertThat(result.faqs()).containsExactly(storeFaq);
        assertThat(capturedMaterials().tools()).containsExactly(AiTool.STORE_SEARCH);
        verify(sentenceFilter).filter(QUERY_VECTOR, List.of(generalFaq));
        verifyNoInteractions(stores, unanswered);
    }

    private void search(List<FaqSearchResponseDto> candidates) {
        DenseFaqCandidates dense = new DenseFaqCandidates(candidates, 5, QUERY_VECTOR);
        when(retrieval.searchCandidates(QUESTION, null, 3, 0)).thenReturn(dense);
        when(retrieval.rankCandidates(QUESTION, dense, 3))
                .thenReturn(new RetrievalOutcome(candidates, candidates.getFirst(), false, null));
    }

    private ChatAnswerProcessor processor() {
        ChatAnswerProcessor processor = new ChatAnswerProcessor(retrieval, ai, unanswered,
                ChatTestFixtures.collector(stores), ChatTestFixtures.disabledCache(), sentenceFilter);
        ReflectionTestUtils.setField(processor, "topK", 3);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
        return processor;
    }

    private AnswerMaterials capturedMaterials() {
        ArgumentCaptor<AnswerMaterials> materials = ArgumentCaptor.captor();
        verify(ai).generateAnswer(materials.capture());
        return materials.getValue();
    }

    private AnswerAttemptsHistory attempt() {
        return AnswerAttemptsHistory.builder().id(100L).userId(1L).question(QUESTION).attemptCount(1)
                .createdAt(LocalDateTime.of(2026, 10, 19, 15, 0)).status("PENDING").build();
    }

    private FaqSearchResponseDto faq(Long id, Intent intent, double score) {
        return new FaqSearchResponseDto(id, "FAQ 질문 " + id, "조회 안내 " + id, score,
                intent, id + 100, "유사 질문 " + id, false);
    }
}
