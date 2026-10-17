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
     * subQuestion은 검색에 쓴 검색어로 중복 판정·묶음(임베딩)·묶음 대표 질문에 쓰고, userText는 관리자 화면에 함께 보여 줄 원문 조각입니다.
     * 질문을 나누지 않았으면 subQuestion과 userText에 원문을, subQuestionIndex에 1을 넘깁니다.
     */
    @Transactional
    public void createUnansweredQuestion(
            Long attemptId,
            int subQuestionIndex,
            String subQuestion,
            String userText,
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
                        // 대표 질문은 목록에 한 문장만 보이므로 맥락이 채워진 검색어를 씁니다.
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
                userText,
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
