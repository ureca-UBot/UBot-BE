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

    @Transactional
    public void createUnansweredQuestion(
            Long attemptId,
            String question,
            UnansweredReason reason,
            Long bestFaqId,
            Double bestSimilarity
    ) {
        if (unansweredQuestionRepository.existsByAttemptId(attemptId)) {
            return;
        }

        PGvector vector = embeddingService.embedText(question);
        Long profileId = embeddingProfileService.getCurrentProfileId();

        Long groupId = unansweredQuestionVectorRepository
                .findNearestGroupId(
                        vector,
                        profileId,
                        groupThreshold
                )
                .orElseGet(
                        () -> unansweredQuestionVectorRepository.saveGroup(
                                question,
                                vector,
                                profileId,
                                bestFaqId
                        )
                );

        unansweredQuestionVectorRepository.saveQuestion(
                attemptId,
                groupId,
                question,
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