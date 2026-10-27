package com.ubot.faq.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.entity.FaqQuestionVariant;
import com.ubot.faq.enums.FaqVectorType;
import com.ubot.faq.repository.FaqQuestionVariantRepository;
import com.ubot.faq.repository.FaqVectorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaqEmbeddingBackfillService {

    private static final int BATCH_SIZE = 100;

    private final FaqQuestionVariantRepository faqQuestionVariantRepository;
    private final FaqVectorRepository faqVectorRepository;
    private final EmbeddingService embeddingService;
    private final EmbeddingProfileService embeddingProfileService;

    public int backfillCurrentProfile() {
        Long profileId =
                embeddingProfileService.getCurrentProfileId();

        int pageNumber = 0;
        int updatedCount = 0;

        Page<FaqQuestionVariant> page;

        do {
            page = faqQuestionVariantRepository.findAllActive(
                    PageRequest.of(
                            pageNumber,
                            BATCH_SIZE,
                            Sort.by("id").ascending()
                    )
            );

            for (FaqQuestionVariant variant : page.getContent()) {
                if (faqVectorRepository.hasCurrentVector(
                        variant.getId(), profileId, FaqVectorType.QUESTION,
                        variant.getOriginFaq().getVersion())) {
                    continue;
                }

                PGvector vector = embeddingService.embedText(variant.getQuestion());
                faqVectorRepository.saveVectorForVariant(
                        variant.getId(), profileId, FaqVectorType.QUESTION,
                        variant.getOriginFaq().getVersion(), vector);
                updatedCount++;
                log.info("FAQ embedding backfill: variantId={}, version={}, profileId={}, vectorType={}",
                        variant.getId(), variant.getOriginFaq().getVersion(), profileId, FaqVectorType.QUESTION);
            }

            pageNumber++;

        } while (page.hasNext());

        log.info(
                "FAQ embedding backfill completed: profileId={}, updated={}",
                profileId,
                updatedCount
        );

        return updatedCount;
    }
}
