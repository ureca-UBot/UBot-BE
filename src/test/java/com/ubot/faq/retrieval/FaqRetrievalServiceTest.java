package com.ubot.faq.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import com.pgvector.PGvector;

import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.service.FaqVectorService;

class FaqRetrievalServiceTest {
    private static final PGvector QUERY_VECTOR = new PGvector(new float[] {1, 0});
    private final FaqVectorService faqVectorService = mock(FaqVectorService.class);

    @Test
    void returnsDenseTopK() {
        List<FaqSearchResponseDto> dense = faqs(3);
        when(faqVectorService.searchWithVector("질문", null, 3))
                .thenReturn(new FaqVectorService.SearchResult(dense, QUERY_VECTOR));

        RetrievalOutcome outcome = searchAndRank(new FaqRetrievalService(faqVectorService), null);

        assertThat(outcome.rankedCandidates()).isSameAs(dense);
        assertThat(outcome.denseTop1()).isSameAs(dense.get(0));
    }

    @Test
    void keepsIntentFilter() {
        when(faqVectorService.searchWithVector("질문", Intent.STORE_DATA, 3))
                .thenReturn(new FaqVectorService.SearchResult(faqs(2), QUERY_VECTOR));

        RetrievalOutcome outcome = searchAndRank(new FaqRetrievalService(faqVectorService),
                Intent.STORE_DATA);

        assertThat(outcome.rankedCandidates()).extracting(FaqSearchResponseDto::faqId).containsExactly(1L, 2L);
        verify(faqVectorService, never()).searchWithVector(anyString(), org.mockito.ArgumentMatchers.isNull(), anyInt());
    }

    @Test
    void returnsEmptyForNoCandidates() {
        when(faqVectorService.searchWithVector("질문", null, 3))
                .thenReturn(new FaqVectorService.SearchResult(List.of(), QUERY_VECTOR));

        RetrievalOutcome outcome = searchAndRank(new FaqRetrievalService(faqVectorService), null);

        assertThat(outcome.rankedCandidates()).isEmpty();
        assertThat(outcome.denseTop1()).isNull();
    }

    @Test
    void denseSearchFails_propagates() {
        when(faqVectorService.searchWithVector("질문", null, 3))
                .thenThrow(new DataAccessResourceFailureException("DB 오류"));

        assertThatThrownBy(() -> searchAndRank(new FaqRetrievalService(faqVectorService), null))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void cacheComparisonDoesNotExpandAnswerCandidates() {
        List<FaqSearchResponseDto> dense = faqs(2);
        when(faqVectorService.searchWithVector("질문", null, 2))
                .thenReturn(new FaqVectorService.SearchResult(dense, QUERY_VECTOR));
        FaqRetrievalService service = new FaqRetrievalService(faqVectorService);

        DenseFaqCandidates candidates = service.searchCandidates("질문", null, 1, 2);
        assertThat(candidates.candidates()).hasSize(2);
        RetrievalOutcome outcome = service.selectCandidates(candidates, 1);

        assertThat(outcome.rankedCandidates()).containsExactly(dense.getFirst());
        verify(faqVectorService).searchWithVector("질문", null, 2);
        verifyNoMoreInteractions(faqVectorService);
    }

    @Test
    void recordsTopSimilarityOfDenseSearch() {
        List<FaqSearchResponseDto> dense = faqs(3);
        when(faqVectorService.searchWithVector("질문", null, 3))
                .thenReturn(new FaqVectorService.SearchResult(dense, QUERY_VECTOR));
        when(faqVectorService.searchWithVector("질문", Intent.GENERAL, 3))
                .thenReturn(new FaqVectorService.SearchResult(List.of(), QUERY_VECTOR));
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        FaqRetrievalService service = new FaqRetrievalService(faqVectorService, registry);

        service.searchCandidates("질문", null, 3, 0);
        service.searchCandidates("질문", Intent.GENERAL, 3, 0);

        var similarity = registry.get("faq.retrieval.top.similarity").tag("scope", "all").summary();
        assertThat(similarity.count()).isEqualTo(1);
        assertThat(similarity.totalAmount()).isEqualTo(dense.getFirst().similarityScore());
        assertThat(registry.find("faq.retrieval.top.similarity").tag("scope", "intent").summary()).isNull();
    }

    private RetrievalOutcome searchAndRank(FaqRetrievalService service, Intent intent) {
        return service.selectCandidates(service.searchCandidates("질문", intent, 3, 0), 3);
    }
    private List<FaqSearchResponseDto> faqs(int count) {
        List<FaqSearchResponseDto> faqs = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            faqs.add(new FaqSearchResponseDto(id, "FAQ 질문 " + id, "FAQ 답변 " + id, 0.9 - id * 0.02, Intent.GENERAL));
        }
        return faqs;
    }
}
