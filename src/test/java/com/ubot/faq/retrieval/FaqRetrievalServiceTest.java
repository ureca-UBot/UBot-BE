package com.ubot.faq.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.rerank.FaqRerankService;
import com.ubot.faq.retrieval.rerank.RerankException;
import com.ubot.faq.retrieval.rerank.RerankerClient;
import com.ubot.faq.service.FaqVectorService;

@DisplayName("FAQ 검색 서비스")
class FaqRetrievalServiceTest {

    private final FaqVectorService faqVectorService = mock(FaqVectorService.class);
    private final List<List<String>> sentDocuments = new ArrayList<>();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("Reranker를 꺼 두면 호출하지 않고 벡터 검색 상위 3개를 그대로 돌려준다")
    void disabled_returnsDenseTopK() {
        List<FaqSearchResponseDto> dense = faqs(3);
        when(faqVectorService.getSimilarList("질문", 3)).thenReturn(dense);
        RerankerClient client = mock(RerankerClient.class);

        RetrievalOutcome outcome = service(properties(false, 10, true), client).retrieve("질문", null, 3);

        assertThat(outcome.rankedCandidates()).isSameAs(dense);
        assertThat(outcome.denseTop1()).isSameAs(dense.get(0));
        assertThat(outcome.reranked()).isFalse();
        assertThat(outcome.fallbackReason()).isNull();
        verify(client, never()).score(anyString(), any());
    }

    @Test
    @DisplayName("Reranker를 켜면 후보 10개를 검색해 점수 순으로 모두 다시 정렬한다")
    void enabled_reranksAllCandidates() {
        List<FaqSearchResponseDto> dense = faqs(10);
        when(faqVectorService.getSimilarList("질문", 10)).thenReturn(dense);
        // 벡터 순위 10위가 가장 높은 점수를 받도록 순위와 반대로 점수를 줍니다.
        RerankerClient client = scores(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0);

        RetrievalOutcome outcome = service(properties(true, 10, true), client).retrieve("질문", null, 3);

        assertThat(outcome.reranked()).isTrue();
        // 후보를 더하거나 빼지 않고 순서만 바꿉니다. 최종 3개로 줄이는 일은 유사도 기준을 적용한 뒤에 호출한 쪽이 합니다.
        assertThat(outcome.rankedCandidates()).extracting(FaqSearchResponseDto::faqId)
                .containsExactly(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
        assertThat(outcome.denseTop1()).isSameAs(dense.get(0));
    }

    @Test
    @DisplayName("다시 정렬해도 FAQ의 질문·답변·의도·코사인 유사도는 벡터 검색 결과 그대로다")
    void enabled_keepsOriginalFaqAndCosine() {
        List<FaqSearchResponseDto> dense = faqs(3);
        when(faqVectorService.getSimilarList("질문", 10)).thenReturn(dense);

        RetrievalOutcome outcome = service(properties(true, 10, true), scores(0.2, 0.9, 0.5)).retrieve("질문", null, 3);

        assertThat(outcome.rankedCandidates()).containsExactly(dense.get(1), dense.get(2), dense.get(0));
        assertThat(outcome.rankedCandidates().get(0).similarityScore()).isEqualTo(dense.get(1).similarityScore());
        assertThat(sentDocuments.get(0)).containsExactly(
                "질문: FAQ 질문 1\n답변: FAQ 답변 1", "질문: FAQ 질문 2\n답변: FAQ 답변 2", "질문: FAQ 질문 3\n답변: FAQ 답변 3");
    }

    @Test
    @DisplayName("재검색에서는 선택한 의도 안에서 후보를 찾아 다시 정렬한다")
    void enabled_keepsIntentFilter() {
        when(faqVectorService.getSimilarListByIntent("질문", Intent.STORE_DATA, 10)).thenReturn(faqs(2));

        RetrievalOutcome outcome = service(properties(true, 10, true), scores(0.1, 0.9))
                .retrieve("질문", Intent.STORE_DATA, 3);

        assertThat(outcome.rankedCandidates()).extracting(FaqSearchResponseDto::faqId).containsExactly(2L, 1L);
        verify(faqVectorService, never()).getSimilarList(anyString(), anyInt());
    }

    @Test
    @DisplayName("후보 수 설정이 20이면 20개를 검색한다")
    void enabled_usesConfiguredCandidateK() {
        when(faqVectorService.getSimilarList("질문", 20)).thenReturn(faqs(2));

        service(properties(true, 20, true), scores(0.5, 0.4)).retrieve("질문", null, 3);

        verify(faqVectorService).getSimilarList("질문", 20);
    }

    @Test
    @DisplayName("후보가 없으면 Reranker를 호출하지 않는다")
    void enabled_noCandidates_skipsReranker() {
        when(faqVectorService.getSimilarList("질문", 10)).thenReturn(List.of());
        RerankerClient client = mock(RerankerClient.class);

        RetrievalOutcome outcome = service(properties(true, 10, true), client).retrieve("질문", null, 3);

        assertThat(outcome.rankedCandidates()).isEmpty();
        assertThat(outcome.denseTop1()).isNull();
        verify(client, never()).score(anyString(), any());
    }

    @Test
    @DisplayName("Reranker 호출이 실패하면 이미 받아 둔 벡터 검색 상위 3개로 답하고 검색을 다시 하지 않는다")
    void rerankerFails_fallsBackToDenseTopKWithoutSearchingAgain() {
        List<FaqSearchResponseDto> dense = faqs(10);
        when(faqVectorService.getSimilarList("질문", 10)).thenReturn(dense);
        RerankerClient client = (query, documents) -> {
            throw new RerankException("제한 시간 초과");
        };

        RetrievalOutcome outcome = service(properties(true, 10, true), client).retrieve("질문", null, 3);

        assertThat(outcome.rankedCandidates()).containsExactly(dense.get(0), dense.get(1), dense.get(2));
        assertThat(outcome.denseTop1()).isSameAs(dense.get(0));
        assertThat(outcome.reranked()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo("제한 시간 초과");
        verify(faqVectorService, times(1)).getSimilarList("질문", 10);
        verifyNoMoreInteractions(faqVectorService);
    }

    @Test
    @DisplayName("점수 개수가 다르거나 유한한 수가 아니면 벡터 검색 결과로 답한다")
    void unusableScores_fallBack() {
        when(faqVectorService.getSimilarList("질문", 10)).thenReturn(faqs(3));

        assertThat(service(properties(true, 10, true), scores(0.9, 0.8)).retrieve("질문", null, 3).fallbackReason())
                .isNotNull();
        assertThat(service(properties(true, 10, true), scores(0.9, Double.NaN, 0.1)).retrieve("질문", null, 3)
                .fallbackReason()).isNotNull();
        assertThat(service(properties(true, 10, true), scores(0.9, null, 0.1)).retrieve("질문", null, 3)
                .fallbackReason()).isNotNull();
        assertThat(service(properties(true, 10, true), (query, documents) -> null).retrieve("질문", null, 3)
                .fallbackReason()).isNotNull();
    }

    @Test
    @DisplayName("벡터 결과로 답하는 설정을 끄면 Reranker 실패를 그대로 알린다")
    void fallbackDisabled_throws() {
        when(faqVectorService.getSimilarList("질문", 10)).thenReturn(faqs(3));
        RerankerClient client = (query, documents) -> {
            throw new RerankException("연결 실패");
        };

        assertThatThrownBy(() -> service(properties(true, 10, false), client).retrieve("질문", null, 3))
                .isInstanceOf(RerankException.class);
    }

    @Test
    @DisplayName("벡터 검색 실패는 Reranker 실패로 감추지 않고 그대로 알린다")
    void denseSearchFails_propagates() {
        when(faqVectorService.getSimilarList("질문", 10)).thenThrow(new DataAccessResourceFailureException("DB 오류"));
        RerankerClient client = mock(RerankerClient.class);

        assertThatThrownBy(() -> service(properties(true, 10, true), client).retrieve("질문", null, 3))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(client, never()).score(anyString(), any());
    }

    @Test
    @DisplayName("Reranker를 기다리는 중에 작업이 취소되면 벡터 결과로 답하지 않고 취소를 알린다")
    void interrupted_propagatesCancellation() {
        when(faqVectorService.getSimilarList("질문", 10)).thenReturn(faqs(3));
        RerankerClient client = (query, documents) -> {
            Thread.currentThread().interrupt();
            throw new RerankException("응답을 기다리다 중단되었습니다.");
        };

        assertThatThrownBy(() -> service(properties(true, 10, true), client).retrieve("질문", null, 3))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    @DisplayName("Reranker가 연속으로 실패하면 이후 요청은 호출하지 않고 벡터 검색 결과로 답한다")
    void repeatedFailures_skipRerankerCalls() {
        List<FaqSearchResponseDto> dense = faqs(10);
        when(faqVectorService.getSimilarList("질문", 10)).thenReturn(dense);
        int[] calls = {0};
        RerankerClient client = (query, documents) -> {
            calls[0]++;
            throw new RerankException("제한 시간 초과");
        };
        FaqRetrievalProperties properties = properties(true, 10, true);
        properties.getRerank().setFailureThreshold(2);
        FaqRetrievalService service = service(properties, client);

        service.retrieve("질문", null, 3);
        service.retrieve("질문", null, 3);
        RetrievalOutcome skipped = service.retrieve("질문", null, 3);

        assertThat(calls[0]).isEqualTo(2);
        assertThat(skipped.rankedCandidates()).containsExactly(dense.get(0), dense.get(1), dense.get(2));
        assertThat(skipped.reranked()).isFalse();
        assertThat(skipped.fallbackReason()).contains("건너뜁니다");
    }

    @Test
    @DisplayName("켠 상태에서 후보 수가 CHAT_TOP_K보다 작으면 시작할 때 막는다")
    void candidateKSmallerThanTopK_rejected() {
        assertThatThrownBy(() -> new FaqRetrievalService(
                faqVectorService, new FaqRerankService(scores()), properties(true, 2, true), 3))
                .isInstanceOf(IllegalStateException.class);
        // 꺼 둔 상태에서는 후보 수를 쓰지 않으므로 막지 않습니다.
        new FaqRetrievalService(faqVectorService, new FaqRerankService(scores()), properties(false, 2, true), 3);
    }

    @Test
    @DisplayName("캐시 비교용으로 늘린 후보는 기존 리랭커 후보 수를 늘리지 않는다")
    void cacheComparisonDoesNotExpandRerankCandidates() {
        List<FaqSearchResponseDto> dense = faqs(2);
        when(faqVectorService.getSimilarList("질문", 2)).thenReturn(dense);
        FaqRetrievalService service = new FaqRetrievalService(
                faqVectorService, new FaqRerankService(scores(0.9)), properties(true, 1, true), 1);

        DenseFaqCandidates candidates = service.searchCandidates("질문", null, 1, 2);
        assertThat(candidates.candidates()).hasSize(2);
        RetrievalOutcome outcome = service.rankCandidates("질문", candidates, 1);

        assertThat(outcome.reranked()).isTrue();
        assertThat(outcome.rankedCandidates()).containsExactly(dense.getFirst());
        assertThat(sentDocuments).singleElement().satisfies(documents -> assertThat(documents).hasSize(1));
        verify(faqVectorService).getSimilarList("질문", 2);
        verifyNoMoreInteractions(faqVectorService);
    }

    private FaqRetrievalService service(FaqRetrievalProperties properties, RerankerClient client) {
        return new FaqRetrievalService(faqVectorService, new FaqRerankService(client), properties, 3);
    }

    private FaqRetrievalProperties properties(boolean enabled, int candidateK, boolean fallbackEnabled) {
        FaqRetrievalProperties properties = new FaqRetrievalProperties();
        properties.getRerank().setEnabled(enabled);
        properties.getRerank().setCandidateK(candidateK);
        properties.getRerank().setFallbackEnabled(fallbackEnabled);
        return properties;
    }

    /** 정해 둔 점수를 입력 순서대로 돌려주는 Reranker입니다. */
    private RerankerClient scores(Double... scores) {
        return (query, documents) -> {
            sentDocuments.add(documents);
            return Arrays.asList(scores);
        };
    }

    /** 벡터 검색 순위대로 코사인 유사도가 낮아지는 FAQ 목록입니다. */
    private List<FaqSearchResponseDto> faqs(int count) {
        List<FaqSearchResponseDto> faqs = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            faqs.add(new FaqSearchResponseDto(id, "FAQ 질문 " + id, "FAQ 답변 " + id, 0.9 - id * 0.02, Intent.GENERAL));
        }
        return faqs;
    }
}
