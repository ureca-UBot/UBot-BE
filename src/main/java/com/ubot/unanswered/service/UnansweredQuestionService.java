package com.ubot.unanswered.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pgvector.PGvector;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.unanswered.repository.UnansweredQuestionVectorRepository;

@Service
public class UnansweredQuestionService {

    private final EmbeddingService embeddingService;
    private final EmbeddingProfileService embeddingProfileService;
    private final UnansweredQuestionRepository unansweredQuestionRepository;
    private final UnansweredQuestionVectorRepository unansweredQuestionVectorRepository;
    private final double groupThreshold;

    public UnansweredQuestionService(
            EmbeddingService embeddingService,
            EmbeddingProfileService embeddingProfileService,
            UnansweredQuestionRepository unansweredQuestionRepository,
            UnansweredQuestionVectorRepository unansweredQuestionVectorRepository,
            @Value("${UNANSWERED_GROUP_THRESHOLD:0.6}") double groupThreshold
    ) {
        this.embeddingService = embeddingService;
        this.embeddingProfileService = embeddingProfileService;
        this.unansweredQuestionRepository = unansweredQuestionRepository;
        this.unansweredQuestionVectorRepository = unansweredQuestionVectorRepository;
        this.groupThreshold = groupThreshold;
    }

    /**
     * 실패한 하위 질문 하나를 미답변으로 저장하고 가까운 묶음에 넣습니다.
     * 질문을 나누지 않았으면 subQuestion에 원문을, subQuestionIndex에 0을 넘깁니다.
     */
    @Transactional
    public void createUnansweredQuestion(
            Long attemptId,
            int subQuestionIndex,
            String subQuestion,
            UnansweredReason reason,
            Long bestFaqId,
            Double bestSimilarity
    ) {
        // 같은 시도의 같은 하위 질문은 한 번만 저장합니다.
        if (unansweredQuestionRepository.existsByAttemptIdAndSubQuestionIndex(attemptId, subQuestionIndex)) {
            return;
        }
        // 재시도가 다시 실패해도 같은 질문이 묶음 건수를 부풀리지 않게 합니다.
        if (unansweredQuestionRepository.existsInSameQuestion(attemptId, subQuestion)) {
            return;
        }

        PGvector vector = embeddingService.embedText(subQuestion);
        Long profileId = embeddingProfileService.getCurrentProfileId();

        Long groupId = unansweredQuestionVectorRepository
                .findNearestGroupId(
                        vector,
                        profileId,
                        groupThreshold
                )
                .orElseGet(
                        () -> unansweredQuestionVectorRepository.saveGroup(
                                subQuestion,
                                vector,
                                profileId,
                                bestFaqId
                        )
                );

        unansweredQuestionVectorRepository.saveQuestion(
                attemptId,
                subQuestionIndex,
                groupId,
                subQuestion,
                vector,
                profileId,
                reason,
                bestFaqId,
                bestSimilarity
        );

        unansweredQuestionVectorRepository.updateGroupCentroid(
                groupId,
                profileId
        );
    }
}