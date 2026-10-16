package com.ubot.faq.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.FaqVectorType;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqVectorRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class FaqVectorService {

    private final EmbeddingService embeddingService;
    private final EmbeddingProfileService embeddingProfileService;
    private final FaqVectorRepository faqVectorRepository;

    public FaqVectorService(
            EmbeddingService embeddingService,
            EmbeddingProfileService embeddingProfileService,
            FaqVectorRepository faqVectorRepository
    ) {
        this.embeddingService = embeddingService;
        this.embeddingProfileService = embeddingProfileService;
        this.faqVectorRepository = faqVectorRepository;
        log.info("FAQ 검색 벡터 유형={}", FaqVectorType.QUESTION);
    }

    public List<FaqSearchResponseDto> getSimilarList(
            String userQuestion,
            int topK
    ) {
        return searchWithVector(userQuestion, null, topK).faqs();
    }

    public List<FaqSearchResponseDto> getSimilarListByIntent(
            String userQuestion,
            Intent intent,
            int topK
    ) {
        Objects.requireNonNull(intent, "intent는 필수입니다.");
        return searchWithVector(userQuestion, intent, topK).faqs();
    }

    /** FAQ 검색 결과와 그 검색에 사용한 사용자 질문 벡터를 함께 돌려줍니다. */
    public SearchResult searchWithVector(String userQuestion, Intent intent, int topK) {
        PGvector queryVector = embeddingService.embedText(userQuestion);
        Long profileId = embeddingProfileService.getCurrentProfileId();
        List<FaqSearchResponseDto> faqs = intent == null
                ? faqVectorRepository.getSimilarList(
                        queryVector, profileId, FaqVectorType.QUESTION, topK)
                : faqVectorRepository.getSimilarListByIntent(
                        queryVector, profileId, FaqVectorType.QUESTION, intent, topK);
        return new SearchResult(faqs, queryVector);
    }

    public record SearchResult(List<FaqSearchResponseDto> faqs, PGvector queryVector) {
    }

    /** 새 variant의 질문 벡터를 저장합니다. */
    public void saveQuestionVectorForVariant(
            Long variantId,
            Integer faqVersion,
            String question
    ) {
        saveVector(variantId, faqVersion, question);
    }

    /** 유지되는 variant는 질문이 같으므로 질문 벡터의 FAQ 버전만 갱신합니다. */
    public void updateQuestionVectorVersionForVariant(
            Long variantId,
            Integer faqVersion
    ) {
        Long profileId = embeddingProfileService.getCurrentProfileId();
        faqVectorRepository.updateVectorVersionForVariant(
                variantId, profileId, faqVersion);
    }

    private void saveVector(
            Long variantId,
            Integer faqVersion,
            String question
    ) {
        long startedAt = System.nanoTime();

        PGvector vector = embeddingService.embedText(question);
        Long profileId = embeddingProfileService.getCurrentProfileId();

        faqVectorRepository.saveVectorForVariant(
                variantId,
                profileId,
                FaqVectorType.QUESTION,
                faqVersion,
                vector
        );

        log.info(
                "FAQ 벡터를 저장했습니다: variantId={}, version={}, profileId={}, 유형={}, 처리시간={}ms",
                variantId,
                faqVersion,
                profileId,
                FaqVectorType.QUESTION,
                elapsedMillis(startedAt)
        );
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
