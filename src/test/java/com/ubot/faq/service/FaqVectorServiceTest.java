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
import com.ubot.ai.config.AiRuntimeProperties;
import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.embedding.repository.EmbeddingProfileRepository;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.dto.response.SemanticFaqCandidateResponseDto;
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

    @Test
    void semanticSearchKeepsTheConfiguredQuestionAnswerBaseline() {
        PGvector vector = vector(0);
        var qaService = serviceSearching(FaqVectorType.QUESTION_ANSWER);
        when(embeddingService.embedText("질문")).thenReturn(vector);
        when(embeddingProfileService.getCurrentProfileId()).thenReturn(10L);
        var baseline = List.of(new FaqSearchResponseDto(1L,"질문","답변",.9,Intent.GENERAL));
        when(repository.getSimilarList(vector,10L,FaqVectorType.QUESTION_ANSWER,3)).thenReturn(baseline);
        when(repository.getSemanticCandidates(vector,10L)).thenReturn(List.of());

        assertThat(qaService.searchWithSemanticCache("질문",3).baseline()).isEqualTo(baseline);
        verify(repository).getSimilarList(vector,10L,FaqVectorType.QUESTION_ANSWER,3);
        verify(repository).getSemanticCandidates(vector,10L);
        verify(embeddingService).embedText("질문");
    }

    @Test
    void semanticSearchReusesOneQueryEmbeddingAndTheCurrentProfile() {
        PGvector vector = vector(0);
        when(embeddingService.embedText("질문")).thenReturn(vector);
        when(embeddingProfileService.getCurrentProfileId()).thenReturn(10L);
        when(repository.getSimilarList(vector,10L, FaqVectorType.QUESTION,3)).thenReturn(List.of());
        when(repository.getSemanticCandidates(vector,10L)).thenReturn(List.of());
        service.searchWithSemanticCache("질문",3);
        verify(embeddingService).embedText("질문");
        verify(repository).getSimilarList(vector,10L, FaqVectorType.QUESTION,3);
        verify(repository).getSemanticCandidates(vector,10L);
    }

    @Test
    void semanticSearchUsesTheSameConfiguredClientAndProfileAsChatForBothEngines() {
        for (String mode : List.of("ollama", "vllm")) {
            var runtime = new AiRuntimeProperties();
            runtime.setMode(mode);
            runtime.setEmbeddingProfileVersion(2);
            var client = mock(EmbeddingClient.class);
            var profiles = mock(EmbeddingProfileRepository.class);
            var vectors = mock(FaqVectorRepository.class);
            String model = mode.equals("ollama") ? "bge-custom-alias" : "ubot-embedding";
            String provider = mode.equals("ollama") ? "ollama" : "openai-compatible";
            PGvector vector = vector(0);
            when(client.embed("질문")).thenReturn(vector);
            when(client.getModelName()).thenReturn(model);
            when(profiles.findOrCreate(provider,model,EmbeddingClient.DIMENSIONS,2)).thenReturn(42L);
            var baseline = List.of(new FaqSearchResponseDto(1L,"질문","답변",.8,Intent.GENERAL));
            var candidates = List.of(new SemanticFaqCandidateResponseDto(
                    new FaqSearchResponseDto(14L,"공식 질문","공식 답변",.98,Intent.GENERAL,true,1),"등록 표현"));
            when(vectors.getSimilarList(vector,42L, FaqVectorType.QUESTION,3)).thenReturn(baseline);
            when(vectors.getSemanticCandidates(vector,42L)).thenReturn(candidates);
            var shared = new FaqVectorService(new EmbeddingService(client),
                    new EmbeddingProfileService(runtime,client,profiles),vectors, FaqVectorType.QUESTION);

            var result = shared.searchWithSemanticCache("질문",3);

            assertThat(result.baseline()).isEqualTo(baseline);
            assertThat(result.semantic()).isEqualTo(candidates);
            verify(client).embed("질문");
            verify(profiles).findOrCreate(provider,model,EmbeddingClient.DIMENSIONS,2);
            verify(vectors).getSimilarList(vector,42L, FaqVectorType.QUESTION,3);
            verify(vectors).getSemanticCandidates(vector,42L);
        }
    }

    @Test
    void aliasDatabaseFailureKeepsTheAlreadyRetrievedBaseline() {
        PGvector vector = vector(0);
        var baseline = List.of(new FaqSearchResponseDto(1L,"질문","답변",.9,Intent.GENERAL));
        when(embeddingService.embedText("질문")).thenReturn(vector);
        when(embeddingProfileService.getCurrentProfileId()).thenReturn(10L);
        when(repository.getSimilarList(vector,10L, FaqVectorType.QUESTION,3)).thenReturn(baseline);
        when(repository.getSemanticCandidates(vector,10L)).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("alias lookup unavailable"));
        var search = service.searchWithSemanticCache("질문",3);
        assertThat(search.baseline()).isEqualTo(baseline);
        assertThat(search.semantic()).isEmpty();
    }

    @Test
    void normalizedCacheInputDoesNotChangeTheOriginalBaselineEmbedding() {
        PGvector rawVector = vector(0);
        float[] values = new float[1024];
        values[1] = 1;
        PGvector normalizedVector = new PGvector(values);
        String raw = "  esim이  뭐야？？？ ";
        when(embeddingService.embedText(raw)).thenReturn(rawVector);
        when(embeddingService.embedText("esim이 뭐야?")).thenReturn(normalizedVector);
        when(embeddingProfileService.getCurrentProfileId()).thenReturn(10L);
        var baseline = List.of(new FaqSearchResponseDto(1L,"질문","답변",.9,Intent.GENERAL));
        when(repository.getSimilarList(rawVector,10L, FaqVectorType.QUESTION,3)).thenReturn(baseline);
        when(repository.getSemanticCandidates(normalizedVector,10L)).thenReturn(List.of());
        assertThat(service.searchWithSemanticCache(raw,3).baseline()).isEqualTo(baseline);
        verify(repository).getSimilarList(rawVector,10L, FaqVectorType.QUESTION,3);
        verify(repository).getSemanticCandidates(normalizedVector,10L);
        verify(embeddingService).embedText(raw);
        verify(embeddingService).embedText("esim이 뭐야?");
    }

    @Test
    void normalizedEmbeddingFailureKeepsThePreviouslyRetrievedBaseline() {
        String raw = "esim이 뭐야??";
        PGvector vector = vector(0);
        when(embeddingService.embedText(raw)).thenReturn(vector);
        when(embeddingService.embedText("esim이 뭐야?")).thenThrow(new com.ubot.embedding.exception.EmbeddingException(
                com.ubot.embedding.exception.EmbeddingErrorCode.EMBEDDING_TIMEOUT));
        when(embeddingProfileService.getCurrentProfileId()).thenReturn(10L);
        var baseline = List.of(new FaqSearchResponseDto(1L,"질문","답변",.9,Intent.GENERAL));
        when(repository.getSimilarList(vector,10L, FaqVectorType.QUESTION,3)).thenReturn(baseline);
        var result = service.searchWithSemanticCache(raw,3);
        assertThat(result.baseline()).isEqualTo(baseline);
        assertThat(result.semantic()).isEmpty();
        verify(repository,never()).getSemanticCandidates(any(),anyLong());
    }
}
