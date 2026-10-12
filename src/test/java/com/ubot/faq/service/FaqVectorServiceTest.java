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

    private static final String QUESTION_ANSWER_TEXT = "질문: 질문\n답변: 답변";

    private final EmbeddingService embeddingService =
            mock(EmbeddingService.class);

    private final EmbeddingProfileService embeddingProfileService =
            mock(EmbeddingProfileService.class);

    private final FaqVectorRepository repository =
            mock(FaqVectorRepository.class);

    private final FaqVectorService service =
            serviceSearching(FaqVectorType.QUESTION);

    @Test
    @DisplayName("사용자 질문을 임베딩한 뒤 현재 프로필과 설정된 벡터 유형으로 유사 FAQ 목록을 조회한다")
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
    @DisplayName("QUESTION_ANSWER로 설정해도 사용자 질문은 그대로 임베딩하고 FAQ 벡터 유형만 바꿔 검색한다")
    void getSimilarList_searchesQuestionAnswerVectorsWithPlainUserQuestion() {
        FaqVectorService questionAnswerService =
                serviceSearching(FaqVectorType.QUESTION_ANSWER);

        PGvector vector = vector(0);
        Long profileId = 10L;

        when(embeddingService.embedText("질문"))
                .thenReturn(vector);

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        questionAnswerService.getSimilarList("질문", 3);

        verify(embeddingService)
                .embedText("질문");

        verify(repository)
                .getSimilarList(
                        vector,
                        profileId,
                        FaqVectorType.QUESTION_ANSWER,
                        3
                );
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
    @DisplayName("새 FAQ는 질문 벡터와 질문+답변 벡터를 각각 임베딩해 현재 프로필과 FAQ 버전으로 저장한다")
    void saveVectorsForFaq_embedsAndSavesEveryVectorType() {
        PGvector questionVector = vector(0);
        PGvector questionAnswerVector = vector(1);
        Long profileId = 10L;

        when(embeddingService.embedText("질문"))
                .thenReturn(questionVector);

        when(embeddingService.embedText(QUESTION_ANSWER_TEXT))
                .thenReturn(questionAnswerVector);

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        service.saveVectorsForFaq(
                1L,
                3,
                "질문",
                "답변"
        );

        verify(repository)
                .saveVectorForFaq(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION,
                        3,
                        questionVector
                );

        verify(repository)
                .saveVectorForFaq(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION_ANSWER,
                        3,
                        questionAnswerVector
                );
    }

    @Test
    @DisplayName("질문이 바뀌면 두 유형 모두 다시 임베딩한다")
    void updateVectorsForFaq_reembedsBothWhenQuestionChanged() {
        PGvector questionVector = vector(0);
        PGvector questionAnswerVector = vector(1);
        Long profileId = 10L;

        when(embeddingService.embedText("질문"))
                .thenReturn(questionVector);

        when(embeddingService.embedText(QUESTION_ANSWER_TEXT))
                .thenReturn(questionAnswerVector);

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        service.updateVectorsForFaq(
                1L,
                4,
                "질문",
                "답변",
                true,
                false
        );

        verify(repository)
                .saveVectorForFaq(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION,
                        4,
                        questionVector
                );

        verify(repository)
                .saveVectorForFaq(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION_ANSWER,
                        4,
                        questionAnswerVector
                );

        verify(repository, never())
                .updateVectorVersionForFaq(
                        anyLong(),
                        anyLong(),
                        any(),
                        anyInt()
                );
    }

    @Test
    @DisplayName("답변만 바뀌면 질문 벡터는 버전만 갱신하고 질문+답변 벡터만 다시 임베딩한다")
    void updateVectorsForFaq_reembedsOnlyQuestionAnswerWhenAnswerChanged() {
        PGvector questionAnswerVector = vector(1);
        Long profileId = 10L;

        when(embeddingService.embedText(QUESTION_ANSWER_TEXT))
                .thenReturn(questionAnswerVector);

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        service.updateVectorsForFaq(
                1L,
                4,
                "질문",
                "답변",
                false,
                true
        );

        verify(repository)
                .updateVectorVersionForFaq(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION,
                        4
                );

        verify(repository)
                .saveVectorForFaq(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION_ANSWER,
                        4,
                        questionAnswerVector
                );

        verify(embeddingService, never())
                .embedText("질문");
    }

    @Test
    @DisplayName("질문과 답변이 그대로면 재임베딩하지 않고 두 유형의 벡터 버전만 갱신한다")
    void updateVectorsForFaq_updatesOnlyVersionsWhenTextIsSame() {
        Long profileId = 10L;

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        service.updateVectorsForFaq(
                1L,
                4,
                "질문",
                "답변",
                false,
                false
        );

        verify(repository)
                .updateVectorVersionForFaq(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION,
                        4
                );

        verify(repository)
                .updateVectorVersionForFaq(
                        1L,
                        profileId,
                        FaqVectorType.QUESTION_ANSWER,
                        4
                );

        verify(embeddingService, never())
                .embedText(anyString());

        verify(repository, never())
                .saveVectorForFaq(
                        anyLong(),
                        anyLong(),
                        any(),
                        anyInt(),
                        any()
                );
    }

    private FaqVectorService serviceSearching(FaqVectorType searchVectorType) {
        return new FaqVectorService(
                embeddingService,
                embeddingProfileService,
                repository,
                searchVectorType
        );
    }

    private PGvector vector(int index) {
        float[] values = new float[1024];
        values[index] = 1.0f;

        return new PGvector(values);
    }
}
