package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
            new FaqVectorService(
                    embeddingService,
                    embeddingProfileService,
                    repository
            );

    @Test
    @DisplayName("사용자 질문을 임베딩한 뒤 현재 프로필로 유사 FAQ 목록을 조회한다")
    void getSimilarList_embedsQuestionThenQueriesRepository() {
        PGvector vector = vector();
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
                        3
                );
    }

    @Test
    @DisplayName("사용자 질문을 임베딩한 뒤 현재 프로필과 intent로 유사 FAQ를 조회한다")
    void getSimilarListByIntent_embedsQuestionThenQueriesRepositoryWithIntent() {
        PGvector vector = vector();
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
                        Intent.STORE_DATA,
                        3
                );

        verify(repository, never())
                .getSimilarList(
                        any(),
                        anyLong(),
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
    @DisplayName("FAQ 질문을 임베딩해 현재 프로필과 FAQ 버전으로 저장한다")
    void saveVectorForFaq_embedsQuestionThenSavesVector() {
        PGvector vector = vector();
        Long profileId = 10L;

        when(embeddingService.embedText("질문"))
                .thenReturn(vector);

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        service.saveVectorForFaq(
                1L,
                3,
                "질문"
        );

        verify(repository)
                .saveVectorForFaq(
                        1L,
                        profileId,
                        3,
                        vector
                );
    }

    @Test
    @DisplayName("질문이 바뀌지 않으면 재임베딩하지 않고 벡터 버전만 갱신한다")
    void updateVectorVersionForFaq_updatesVersionWithoutEmbedding() {
        Long profileId = 10L;

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        service.updateVectorVersionForFaq(
                1L,
                4
        );

        verify(repository)
                .updateVectorVersionForFaq(
                        1L,
                        profileId,
                        4
                );

        verifyNoInteractions(embeddingService);
    }

    private PGvector vector() {
        float[] values = new float[1024];
        values[0] = 1.0f;

        return new PGvector(values);
    }
}