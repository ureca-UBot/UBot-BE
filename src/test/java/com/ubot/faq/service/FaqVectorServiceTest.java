package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.FaqVectorType;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqVectorRepository;

@DisplayName("FAQ 벡터 서비스 테스트")
class FaqVectorServiceTest {

    private final EmbeddingService embeddingService =
            mock(EmbeddingService.class);

    private final EmbeddingProfileService embeddingProfileService =
            mock(EmbeddingProfileService.class);

    private final FaqVectorRepository repository =
            mock(FaqVectorRepository.class);

    private final FaqVectorService service =
            new FaqVectorService(embeddingService, embeddingProfileService, repository);

    @Test
    @DisplayName("사용자 질문을 임베딩한 뒤 현재 프로필의 QUESTION 벡터로 유사 FAQ 목록을 조회한다")
    void getSimilarList_embedsQuestionThenQueriesRepository() {
        PGvector vector = vector(0);
        Long profileId = 10L;

        List<FaqSearchResponseDto> expected = List.of(
                new FaqSearchResponseDto(
                        1L,
                        "질문",
                        "답변",
                        0.9,
                        Intent.GENERAL
                )
        );

        when(embeddingService.embedText("질문"))
                .thenReturn(vector);

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        when(repository.getSimilarList(
                vector,
                profileId,
                FaqVectorType.QUESTION,
                3
        )).thenReturn(expected);

        assertThat(
                service.getSimilarList("질문", 3)
        ).isEqualTo(expected);

        verify(embeddingService)
                .embedText("질문");

        verify(embeddingProfileService)
                .getCurrentProfileId();

        verify(repository)
                .getSimilarList(
                        vector,
                        profileId,
                        FaqVectorType.QUESTION,
                        3
                );
    }

    @Test
    @DisplayName("검색 결과와 같은 질문 벡터를 함께 반환하고 질문은 한 번만 임베딩한다")
    void searchWithVector_returnsQueryVector() {
        PGvector vector = vector(0);
        List<FaqSearchResponseDto> expected = List.of(new FaqSearchResponseDto(1L, "질문", "답변", 0.9, Intent.GENERAL));
        when(embeddingService.embedText("질문")).thenReturn(vector);
        when(embeddingProfileService.getCurrentProfileId()).thenReturn(10L);
        when(repository.getSimilarList(vector, 10L, FaqVectorType.QUESTION, 3)).thenReturn(expected);

        FaqVectorService.SearchResult result = service.searchWithVector("질문", null, 3);

        assertThat(result.faqs()).isSameAs(expected);
        assertThat(result.queryVector()).isSameAs(vector);
        verify(embeddingService).embedText("질문");
    }

    @Test
    @DisplayName("사용자 질문을 임베딩한 뒤 현재 프로필과 intent로 유사 FAQ를 조회한다")
    void getSimilarListByIntent_embedsQuestionThenQueriesRepositoryWithIntent() {
        PGvector vector = vector(0);
        Long profileId = 10L;

        List<FaqSearchResponseDto> expected = List.of(
                new FaqSearchResponseDto(
                        2L,
                        "매장 질문",
                        "매장 답변",
                        0.9,
                        Intent.STORE_DATA
                )
        );

        when(embeddingService.embedText("질문"))
                .thenReturn(vector);

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        when(repository.getSimilarListByIntent(
                vector,
                profileId,
                FaqVectorType.QUESTION,
                Intent.STORE_DATA,
                3
        )).thenReturn(expected);

        assertThat(
                service.getSimilarListByIntent(
                        "질문",
                        Intent.STORE_DATA,
                        3
                )
        ).isEqualTo(expected);

        verify(repository)
                .getSimilarListByIntent(
                        vector,
                        profileId,
                        FaqVectorType.QUESTION,
                        Intent.STORE_DATA,
                        3
                );

        verify(repository, never())
                .getSimilarList(
                        any(),
                        anyLong(),
                        any(),
                        anyInt()
                );
    }

    @Test
    @DisplayName("intent가 없으면 임베딩과 프로필 조회를 하지 않고 거절한다")
    void getSimilarListByIntent_rejectsNullIntent() {
        assertThatThrownBy(
                () -> service.getSimilarListByIntent(
                        "질문",
                        null,
                        3
                )
        ).isInstanceOf(NullPointerException.class);

        verifyNoInteractions(
                embeddingService,
                embeddingProfileService,
                repository
        );
    }

    @Test
    @DisplayName("새 variant는 질문 벡터만 저장한다")
    void saveQuestionVectorForVariant_savesQuestionVector() {
        PGvector questionVector = vector(0);
        Long profileId = 10L;

        when(embeddingService.embedText("질문"))
                .thenReturn(questionVector);

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        service.saveQuestionVectorForVariant(1L, 3, "질문");

        verify(repository)
                .saveVectorForVariant(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION,
                        3,
                        questionVector
                );

        verify(embeddingService, never()).embedText("질문: 질문\n답변: 답변");
    }

    @Test
    @DisplayName("답변이 바뀌어도 유지되는 질문 벡터는 버전만 갱신한다")
    void updateQuestionVectorVersionForVariant_updatesVersionOnly() {
        Long profileId = 10L;

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        service.updateQuestionVectorVersionForVariant(1L, 4);

        verify(repository)
                .updateVectorVersionForVariant(
                        1L,
                        profileId,
                        4
                );

        verify(embeddingService, never())
                .embedText(anyString());

        verify(repository, never())
                .saveVectorForVariant(
                        anyLong(),
                        anyLong(),
                        any(),
                        anyInt(),
                        any()
                );
    }

    @Test
    @DisplayName("DB 검색 시간만 검색 범위(전체·의도)별로 기록한다")
    void recordsVectorSearchTimeByScope() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var metered = new FaqVectorService(embeddingService, embeddingProfileService, repository, registry);
        when(embeddingService.embedText(anyString())).thenReturn(vector(0));
        when(embeddingProfileService.getCurrentProfileId()).thenReturn(10L);

        metered.getSimilarList("질문", 3);
        metered.getSimilarListByIntent("질문", Intent.GENERAL, 3);
        metered.getSimilarListByIntent("질문", Intent.GENERAL, 3);

        assertThat(registry.get("faq.vector.search").tag("scope", "all").timer().count()).isEqualTo(1);
        assertThat(registry.get("faq.vector.search").tag("scope", "intent").timer().count()).isEqualTo(2);
    }

    private PGvector vector(int index) {
        float[] values = new float[1024];
        values[index] = 1.0f;

        return new PGvector(values);
    }
}
