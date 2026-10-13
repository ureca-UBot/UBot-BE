package com.ubot.faq.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.embedding.exception.EmbeddingException;
import com.ubot.faq.policy.FaqQuestionNormalizer;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.dto.response.FaqAnswerSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqVectorRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaqVectorService {

    private final EmbeddingService embeddingService;
    private final EmbeddingProfileService embeddingProfileService;
    private final FaqVectorRepository faqVectorRepository;

    /** 원문 검색을 유지하고, 캐시 입력이 같으면 질문 임베딩을 재사용합니다. */
    public FaqAnswerSearchResponseDto searchWithSemanticCache(String question, int topK) {
        PGvector vector = embeddingService.embedText(question);
        Long profileId = embeddingProfileService.getCurrentProfileId();
        var baseline = faqVectorRepository.getSimilarList(vector, profileId, topK);
        try {
            String normalized = FaqQuestionNormalizer.normalize(question);
            PGvector cacheVector = question.equals(normalized) ? vector : embeddingService.embedText(normalized);
            return new FaqAnswerSearchResponseDto(baseline, faqVectorRepository.getSemanticCandidates(cacheVector, profileId));
        } catch (DataAccessException | EmbeddingException exception) {
            log.warn("SEMANTIC_CACHE lookup failed; keeping baseline retrieval", exception);
            return new FaqAnswerSearchResponseDto(baseline, List.of());
        }
    }

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
