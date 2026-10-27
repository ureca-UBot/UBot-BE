package com.ubot.faq.service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.FaqVectorType;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqVectorRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class FaqVectorService {

    private final EmbeddingService embeddingService;
    private final EmbeddingProfileService embeddingProfileService;
    private final FaqVectorRepository faqVectorRepository;
    // 지표를 보지 않는 테스트는 전역 레지스트리를 씀. 등록된 저장소가 없으면 기록하지 않음
    private final MeterRegistry metrics;

    public FaqVectorService(
            EmbeddingService embeddingService,
            EmbeddingProfileService embeddingProfileService,
            FaqVectorRepository faqVectorRepository
    ) {
        this(embeddingService, embeddingProfileService, faqVectorRepository, Metrics.globalRegistry);
    }

    @Autowired
    public FaqVectorService(
            EmbeddingService embeddingService,
            EmbeddingProfileService embeddingProfileService,
            FaqVectorRepository faqVectorRepository,
            MeterRegistry metrics
    ) {
        this.embeddingService = embeddingService;
        this.embeddingProfileService = embeddingProfileService;
        this.faqVectorRepository = faqVectorRepository;
        this.metrics = metrics;
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
        long searchStartedAt = System.nanoTime();
        List<FaqSearchResponseDto> faqs = intent == null
                ? faqVectorRepository.getSimilarList(
                        queryVector, profileId, FaqVectorType.QUESTION, topK)
                : faqVectorRepository.getSimilarListByIntent(
                        queryVector, profileId, FaqVectorType.QUESTION, intent, topK);
        // DB 검색 시간만 셈. 질문 임베딩 시간은 embedding.requests에 따로 남음
        metrics.timer("faq.vector.search", "scope", intent == null ? "all" : "intent")
                .record(System.nanoTime() - searchStartedAt, TimeUnit.NANOSECONDS);
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
