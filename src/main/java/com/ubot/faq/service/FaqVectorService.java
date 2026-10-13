package com.ubot.faq.service;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
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

    // 검색할 FAQ 벡터 유형입니다. 사용자 질문은 유형과 관계없이 질문만 임베딩합니다.
    private final FaqVectorType searchVectorType;

    public FaqVectorService(
            EmbeddingService embeddingService,
            EmbeddingProfileService embeddingProfileService,
            FaqVectorRepository faqVectorRepository,
            @Value("${FAQ_SEARCH_VECTOR_TYPE:QUESTION}") FaqVectorType searchVectorType
    ) {
        this.embeddingService = embeddingService;
        this.embeddingProfileService = embeddingProfileService;
        this.faqVectorRepository = faqVectorRepository;
        // 값을 비워 두면 null로 들어옵니다. 검색할 때가 아니라 시작할 때 알 수 있게 여기서 막습니다.
        this.searchVectorType = Objects.requireNonNull(
                searchVectorType,
                "FAQ_SEARCH_VECTOR_TYPE은 QUESTION 또는 QUESTION_ANSWER여야 합니다."
        );

        log.info("FAQ 검색에 쓸 벡터 유형을 정했습니다: 유형={}", this.searchVectorType);
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
                searchVectorType,
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
                searchVectorType,
                intent,
                topK
        );
    }

    /** 새 FAQ의 벡터를 유형마다 하나씩 만들어 저장합니다. */
    public void saveVectorsForFaq(
            Long faqId,
            Integer faqVersion,
            String question,
            String answer
    ) {
        for (FaqVectorType vectorType : FaqVectorType.values()) {
            saveVector(
                    faqId,
                    faqVersion,
                    vectorType,
                    question,
                    answer
            );
        }
    }

    /** 수정한 FAQ의 벡터를 맞춥니다. 바뀐 텍스트가 들어가는 유형만 다시 임베딩하고, 나머지는 FAQ 버전만 갱신합니다. */
    public void updateVectorsForFaq(
            Long faqId,
            Integer faqVersion,
            String question,
            String answer,
            boolean questionChanged,
            boolean answerChanged
    ) {
        for (FaqVectorType vectorType : FaqVectorType.values()) {
            if (vectorType.needsReembedding(questionChanged, answerChanged)) {
                saveVector(
                        faqId,
                        faqVersion,
                        vectorType,
                        question,
                        answer
                );
                continue;
            }

            faqVectorRepository.updateVectorVersionForFaq(
                    faqId,
                    embeddingProfileService.getCurrentProfileId(),
                    vectorType,
                    faqVersion
            );
        }
    }

    private void saveVector(
            Long faqId,
            Integer faqVersion,
            FaqVectorType vectorType,
            String question,
            String answer
    ) {
        long startedAt = System.nanoTime();

        PGvector vector = embeddingService.embedText(
                vectorType.toEmbeddingText(question, answer)
        );
        Long profileId = embeddingProfileService.getCurrentProfileId();

        faqVectorRepository.saveVectorForFaq(
                faqId,
                profileId,
                vectorType,
                faqVersion,
                vector
        );

        log.info(
                "FAQ 벡터를 저장했습니다: FAQID={}, version={}, profileId={}, 유형={}, 처리시간={}ms",
                faqId,
                faqVersion,
                profileId,
                vectorType,
                elapsedMillis(startedAt)
        );
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
