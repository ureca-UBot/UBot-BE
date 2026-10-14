package com.ubot.faq.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.ubot.faq.entity.FaqQuestionVariant;
import com.ubot.faq.enums.FaqVectorType;
import com.ubot.faq.repository.FaqQuestionVariantRepository;
import com.ubot.faq.repository.FaqVectorRepository;

@DisplayName("FAQ 임베딩 백필 서비스 테스트")
class FaqEmbeddingBackfillServiceTest {

    private static final Long PROFILE_ID = 10L;

    private final FaqQuestionVariantRepository variantRepository =
            mock(FaqQuestionVariantRepository.class);

    private final FaqVectorRepository faqVectorRepository =
            mock(FaqVectorRepository.class);

    private final EmbeddingService embeddingService =
            mock(EmbeddingService.class);

    private final EmbeddingProfileService embeddingProfileService =
            mock(EmbeddingProfileService.class);

    private final FaqEmbeddingBackfillService service =
            new FaqEmbeddingBackfillService(
                    variantRepository,
                    faqVectorRepository,
                    embeddingService,
                    embeddingProfileService
            );

    @Test
    @DisplayName("현재 프로필 벡터가 없는 FAQ만 유형별로 임베딩해서 저장한다")
    void backfillCurrentProfile_embedsOnlyMissingFaqs() {
        Faq existing = faq(
                1L,
                "이미 벡터가 있는 질문",
                "이미 벡터가 있는 답변",
                1
        );

        Faq missing = faq(
                2L,
                "벡터가 없는 질문",
                "벡터가 없는 답변",
                3
        );

        givenActiveFaqs(existing, missing);
        givenCurrentVector(existing, FaqVectorType.QUESTION, true);
        givenCurrentVector(existing, FaqVectorType.QUESTION_ANSWER, true);
        givenCurrentVector(missing, FaqVectorType.QUESTION, false);
        givenCurrentVector(missing, FaqVectorType.QUESTION_ANSWER, false);

        PGvector questionVector = vector(0);
        PGvector questionAnswerVector = vector(1);

        when(
                embeddingService.embedText(
                        "벡터가 없는 질문"
                )
        ).thenReturn(questionVector);

        when(
                embeddingService.embedText(
                        "질문: 벡터가 없는 질문\n답변: 벡터가 없는 답변"
                )
        ).thenReturn(questionAnswerVector);

        int updated =
                service.backfillCurrentProfile();

        assertThat(updated)
                .isEqualTo(2);

        verify(embeddingService, never())
                .embedText(
                        "이미 벡터가 있는 질문"
                );

        verify(faqVectorRepository)
                .saveVectorForVariant(
                        2L,
                        PROFILE_ID,
                        FaqVectorType.QUESTION,
                        3,
                        questionVector
                );

        verify(faqVectorRepository)
                .saveVectorForVariant(
                        2L,
                        PROFILE_ID,
                        FaqVectorType.QUESTION_ANSWER,
                        3,
                        questionAnswerVector
                );
    }

    @Test
    @DisplayName("질문 벡터가 이미 있어도 질문+답변 벡터가 없으면 그 유형만 만든다")
    void backfillCurrentProfile_fillsQuestionAnswerWhenQuestionExists() {
        Faq faq = faq(
                1L,
                "질문",
                "답변",
                2
        );

        givenActiveFaqs(faq);
        givenCurrentVector(faq, FaqVectorType.QUESTION, true);
        givenCurrentVector(faq, FaqVectorType.QUESTION_ANSWER, false);

        PGvector questionAnswerVector = vector(1);

        when(
                embeddingService.embedText(
                        "질문: 질문\n답변: 답변"
                )
        ).thenReturn(questionAnswerVector);

        int updated =
                service.backfillCurrentProfile();

        assertThat(updated)
                .isEqualTo(1);

        verify(embeddingService, never())
                .embedText("질문");

        verify(faqVectorRepository)
                .saveVectorForVariant(
                        1L,
                        PROFILE_ID,
                        FaqVectorType.QUESTION_ANSWER,
                        2,
                        questionAnswerVector
                );

        verify(
                faqVectorRepository,
                never()
        ).saveVectorForVariant(
                anyLong(),
                anyLong(),
                eq(FaqVectorType.QUESTION),
                anyInt(),
                any(PGvector.class)
        );
    }

    @Test
    @DisplayName("현재 프로필에 두 유형의 벡터가 모두 있으면 임베딩하지 않는다")
    void backfillCurrentProfile_skipsWhenEverythingExists() {
        Faq faq = faq(
                1L,
                "질문",
                "답변",
                2
        );

        givenActiveFaqs(faq);
        givenCurrentVector(faq, FaqVectorType.QUESTION, true);
        givenCurrentVector(faq, FaqVectorType.QUESTION_ANSWER, true);

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
        ).saveVectorForVariant(
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    private void givenActiveFaqs(Faq... faqs) {
        when(embeddingProfileService.getCurrentProfileId())
                .thenReturn(PROFILE_ID);

        when(variantRepository.findAllActive(any(Pageable.class)))
                .thenReturn(
                        new PageImpl<>(
                                java.util.Arrays.stream(faqs)
                                        .map(faq -> FaqQuestionVariant.builder()
                                                .id(faq.getId())
                                                .originFaq(faq)
                                                .question(faq.getQuestion())
                                                .build())
                                        .toList()
                        )
                );
    }

    private void givenCurrentVector(
            Faq faq,
            FaqVectorType vectorType,
            boolean exists
    ) {
        when(
                faqVectorRepository.hasCurrentVector(
                        faq.getId(),
                        PROFILE_ID,
                        vectorType,
                        faq.getVersion()
                )
        ).thenReturn(exists);
    }

    private Faq faq(
            Long id,
            String question,
            String answer,
            int version
    ) {
        return Faq.builder()
                .id(id)
                .question(question)
                .answer(answer)
                .version(version)
                .build();
    }

    private PGvector vector(int index) {
        float[] values = new float[1024];
        values[index] = 1.0f;

        return new PGvector(values);
    }
}
