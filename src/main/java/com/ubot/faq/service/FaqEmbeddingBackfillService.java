package com.ubot.faq.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.faq.repository.FaqVectorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaqEmbeddingBackfillService {

    private static final int BATCH_SIZE = 100;

    private final FaqRepository faqRepository;
    private final FaqVectorRepository faqVectorRepository;
    private final EmbeddingService embeddingService;
    private final EmbeddingProfileService embeddingProfileService;

    public int backfillCurrentProfile() {
        Long profileId =
                embeddingProfileService.getCurrentProfileId();

        int pageNumber = 0;
        int updatedCount = 0;

        Page<Faq> page;

        do {
            page = faqRepository.findAllByDeletedAtIsNull(
                    PageRequest.of(
                            pageNumber,
                            BATCH_SIZE,
                            Sort.by("id").ascending()
                    )
            );

            for (Faq faq : page.getContent()) {
                if (
                        faqVectorRepository.hasCurrentVector(
                                faq.getId(),
                                profileId,
                                faq.getVersion()
                        )
                ) {
                    continue;
                }

                PGvector vector =
                        embeddingService.embedText(
                                faq.getQuestion()
                        );

                faqVectorRepository.saveVectorForFaq(
                        faq.getId(),
                        profileId,
                        faq.getVersion(),
                        vector
                );

                updatedCount++;

                log.info(
                        "FAQ embedding backfill: faqId={}, version={}, profileId={}",
                        faq.getId(),
                        faq.getVersion(),
                        profileId
                );
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