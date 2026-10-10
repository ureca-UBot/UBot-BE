package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.repository.FaqVectorRepository;

@DisplayName("FAQ 임베딩 백필 서비스 테스트")
class FaqEmbeddingBackfillServiceTest {

    private final FaqRepository faqRepository =
            mock(FaqRepository.class);

    private final FaqVectorRepository faqVectorRepository =
            mock(FaqVectorRepository.class);

    private final EmbeddingService embeddingService =
            mock(EmbeddingService.class);

    private final EmbeddingProfileService embeddingProfileService =
            mock(EmbeddingProfileService.class);

    private final FaqEmbeddingBackfillService service =
            new FaqEmbeddingBackfillService(
                    faqRepository,
                    faqVectorRepository,
                    embeddingService,
                    embeddingProfileService
            );

    @Test
    @DisplayName("현재 프로필 벡터가 없는 FAQ만 임베딩해서 저장한다")
    void backfillCurrentProfile_embedsOnlyMissingFaqs() {
        Long profileId = 10L;

        Faq existing = faq(
                1L,
                "이미 벡터가 있는 질문",
                1
        );

        Faq missing = faq(
                2L,
                "벡터가 없는 질문",
                3
        );

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        when(faqRepository.findAllByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(
                        new PageImpl<>(
                                List.of(existing, missing)
                        )
                );

        when(
                faqVectorRepository.hasCurrentVector(
                        1L,
                        profileId,
                        1
                )
        ).thenReturn(true);

        when(
                faqVectorRepository.hasCurrentVector(
                        2L,
                        profileId,
                        3
                )
        ).thenReturn(false);

        PGvector vector = vector();

        when(
                embeddingService.embedText(
                        "벡터가 없는 질문"
                )
        ).thenReturn(vector);

        int updated =
                service.backfillCurrentProfile();

        assertThat(updated)
                .isEqualTo(1);

        verify(embeddingService, never())
                .embedText(
                        "이미 벡터가 있는 질문"
                );

        verify(embeddingService)
                .embedText(
                        "벡터가 없는 질문"
                );

        verify(faqVectorRepository)
                .saveVectorForFaq(
                        2L,
                        profileId,
                        3,
                        vector
                );
    }

    @Test
    @DisplayName("현재 프로필의 모든 FAQ 벡터가 있으면 임베딩하지 않는다")
    void backfillCurrentProfile_skipsWhenEverythingExists() {
        Long profileId = 10L;

        Faq faq = faq(
                1L,
                "질문",
                2
        );

        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(profileId);

        when(faqRepository.findAllByDeletedAtIsNull(any(Pageable.class)))
                .thenReturn(
                        new PageImpl<>(
                                List.of(faq)
                        )
                );

        when(
                faqVectorRepository.hasCurrentVector(
                        1L,
                        profileId,
                        2
                )
        ).thenReturn(true);

        int updated =
                service.backfillCurrentProfile();

        assertThat(updated)
                .isZero();

        verify(
                embeddingService,
                never()
        ).embedText(any());

        verify(
                faqVectorRepository,
                never()
        ).saveVectorForFaq(
                any(),
                any(),
                any(),
                any()
        );
    }

    private Faq faq(
            Long id,
            String question,
            int version
    ) {
        return Faq.builder()
                .id(id)
                .question(question)
                .version(version)
                .build();
    }

    private PGvector vector() {
        float[] values = new float[1024];
        values[0] = 1.0f;

        return new PGvector(values);
    }
}