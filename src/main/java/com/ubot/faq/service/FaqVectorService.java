package com.ubot.faq.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqVectorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaqVectorService {

    private final EmbeddingService embeddingService;
    private final EmbeddingProfileService embeddingProfileService;
    private final FaqVectorRepository faqVectorRepository;

    public List<FaqSearchResponseDto> getSimilarList(
            String userQuestion,
            int topK
    ) {
        PGvector queryVector = embeddingService.embedText(userQuestion);
        Long profileId = embeddingProfileService.getCurrentProfileId();

        return faqVectorRepository.getSimilarList(
                queryVector,
                profileId,
                topK
        );
    }

    public List<FaqSearchResponseDto> getSimilarListByIntent(
            String userQuestion,
            Intent intent,
            int topK
    ) {
        Objects.requireNonNull(intent, "intent는 필수입니다.");

        PGvector queryVector = embeddingService.embedText(userQuestion);
        Long profileId = embeddingProfileService.getCurrentProfileId();

        return faqVectorRepository.getSimilarListByIntent(
                queryVector,
                profileId,
                intent,
                topK
        );
    }

    public void saveVectorForFaq(
            Long faqId,
            Integer faqVersion,
            String question
    ) {
        long startedAt = System.nanoTime();

        PGvector vector = embeddingService.embedText(question);
        Long profileId = embeddingProfileService.getCurrentProfileId();

        faqVectorRepository.saveVectorForFaq(
                faqId,
                profileId,
                faqVersion,
                vector
        );

        log.info(
                "FAQ 벡터를 저장했습니다: FAQID={}, version={}, profileId={}, 처리시간={}ms",
                faqId,
                faqVersion,
                profileId,
                elapsedMillis(startedAt)
        );
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
    public void updateVectorVersionForFaq(
            Long faqId,
            Integer faqVersion
    ) {
        Long profileId = embeddingProfileService.getCurrentProfileId();

        faqVectorRepository.updateVectorVersionForFaq(
                faqId,
                profileId,
                faqVersion
        );
    }
}