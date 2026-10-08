package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqVectorRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FAQ 벡터 서비스 테스트")
class FaqVectorServiceTest {
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final FaqVectorRepository repository = mock(FaqVectorRepository.class);
    private final FaqVectorService service = new FaqVectorService(embeddingService, repository);

    @Test
    @DisplayName("사용자 질문을 임베딩한 뒤 유사 FAQ 목록을 조회한다")
    void getSimilarList_embedsQuestionThenQueriesRepository() {
        PGvector vector = vector();
        var expected = List.of(new FaqSearchResponseDto(1L, "질문", "답변", 0.9, Intent.GENERAL));
        when(embeddingService.embedText("질문")).thenReturn(vector);
        when(repository.getSimilarList(vector, 3)).thenReturn(expected);

        assertThat(service.getSimilarList("질문", 3)).isEqualTo(expected);
        verify(repository).getSimilarList(vector, 3);
    }

    @Test
    @DisplayName("사용자 질문을 임베딩한 뒤 선택한 intent의 유사 FAQ 목록을 조회한다")
    void getSimilarListByIntent_embedsQuestionThenQueriesRepositoryWithIntent() {
        PGvector vector = vector();
        var expected = List.of(new FaqSearchResponseDto(2L, "매장 질문", "매장 답변", 0.9, Intent.STORE_DATA));
        when(embeddingService.embedText("질문")).thenReturn(vector);
        when(repository.getSimilarListByIntent(vector, Intent.STORE_DATA, 3)).thenReturn(expected);

        assertThat(service.getSimilarListByIntent("질문", Intent.STORE_DATA, 3)).isEqualTo(expected);
        verify(repository).getSimilarListByIntent(vector, Intent.STORE_DATA, 3);
        verify(repository, never()).getSimilarList(any(), anyInt());
    }

    @Test
    @DisplayName("intent가 없으면 임베딩과 조회를 하지 않고 거절한다")
    void getSimilarListByIntent_rejectsNullIntent() {
        assertThatThrownBy(() -> service.getSimilarListByIntent("질문", null, 3))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(embeddingService, repository);
    }

    @Test
    @DisplayName("FAQ 질문을 임베딩해 해당 FAQ의 벡터를 저장한다")
    void saveVectorForFaq_embedsQuestionThenSavesVector() {
        PGvector vector = vector();
        when(embeddingService.embedText("질문")).thenReturn(vector);

        service.saveVectorForFaq(1L, "질문");

        verify(repository).saveVectorForFaq(1L, vector);
    }

    private PGvector vector() {
        return new PGvector(new float[] { 1.0f });
    }
}