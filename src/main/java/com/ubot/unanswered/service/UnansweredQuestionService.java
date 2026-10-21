package com.ubot.unanswered.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pgvector.PGvector;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.repository.AnswerAttemptsHistoryRepository;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.embedding.service.EmbeddingService;
import com.ubot.unanswered.enums.UnansweredReason;
import com.ubot.unanswered.enums.UnansweredSource;
import com.ubot.unanswered.repository.UnansweredQuestionRepository;
import com.ubot.unanswered.repository.UnansweredQuestionVectorRepository;
import com.ubot.unanswered.service.RejectedQuestionDuplicateChecker.QuestionEmbedding;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import com.ubot.outbox.OutboxEventRepository;
import com.ubot.outbox.OutboxEventType;
import com.ubot.unanswered.dto.UnansweredCandidate;
import java.util.Optional;
import java.util.OptionalDouble;

@Service
public class UnansweredQuestionService {

    private final EmbeddingService embeddingService;
    private final EmbeddingProfileService embeddingProfileService;
    private final UnansweredQuestionRepository unansweredQuestionRepository;
    private final UnansweredQuestionVectorRepository unansweredQuestionVectorRepository;
    private final RejectedQuestionDuplicateChecker rejectedQuestionDuplicateChecker;
    private final AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final double groupThreshold;

    public UnansweredQuestionService(
            EmbeddingService embeddingService,
            EmbeddingProfileService embeddingProfileService,
            UnansweredQuestionRepository unansweredQuestionRepository,
            UnansweredQuestionVectorRepository unansweredQuestionVectorRepository,
            RejectedQuestionDuplicateChecker rejectedQuestionDuplicateChecker,
            AnswerAttemptsHistoryRepository answerAttemptsHistoryRepository,
            OutboxEventRepository outboxEventRepository,
            @Value("${UNANSWERED_GROUP_THRESHOLD:0.6}") double groupThreshold
    ) {
        this.embeddingService = embeddingService;
        this.embeddingProfileService = embeddingProfileService;
        this.unansweredQuestionRepository = unansweredQuestionRepository;
        this.unansweredQuestionVectorRepository = unansweredQuestionVectorRepository;
        this.rejectedQuestionDuplicateChecker = rejectedQuestionDuplicateChecker;
        this.answerAttemptsHistoryRepository = answerAttemptsHistoryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.groupThreshold = groupThreshold;
    }

    public void enqueueUnansweredQuestion(Long attemptId, UnansweredCandidate candidate) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("attemptId", attemptId);
        payload.put("subQuestionIndex", candidate.subQuestionIndex());
        payload.put("subQuestion", candidate.subQuestion());
        payload.put("userText", candidate.userText());
        payload.put("reason", candidate.reason().name());
        payload.put("bestFaqId", candidate.bestFaqId());
        payload.put("bestSimilarity", candidate.bestSimilarity());
        payload.put("source", candidate.source().name());
        if (candidate.queryEmbedding() != null && candidate.embeddingProfileId() != null) {
            payload.put("embeddingProfileId", candidate.embeddingProfileId());
            payload.put("queryEmbedding", candidate.queryEmbedding());
        }
        if (candidate.rejectedThreshold() != null) payload.put("rejectedThreshold", candidate.rejectedThreshold());
        outboxEventRepository.enqueue(OutboxEventType.UNANSWERED_QUESTION,
                attemptId + ":" + candidate.subQuestionIndex(), payload);
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
        createUnansweredQuestion(attemptId, subQuestionIndex, subQuestion, userText, reason, bestFaqId, bestSimilarity,
                UnansweredSource.RETRIEVAL);
    }

    @Transactional
    public void createUnansweredQuestion(
            Long attemptId,
            int subQuestionIndex,
            String subQuestion,
            String userText,
            UnansweredReason reason,
            Long bestFaqId,
            Double bestSimilarity,
            UnansweredSource source
    ) {
        createUnansweredQuestion(attemptId, subQuestionIndex, subQuestion, userText, reason, bestFaqId, bestSimilarity,
                source, null, OptionalDouble.empty());
    }

    /**
     * 기존 저장·대기 묶기를 공유합니다. 출력 가드는 이미 선별한 정상 FAQ 부족 후보만 이 경로로 넘깁니다.
     * 반려 기준이 없으면 자동 생략하지 않으며, 확정된 반려 중복이어도 사용자 답변 흐름은 바꾸지 않습니다.
     */
    @Transactional
    public void createUnansweredQuestion(
            Long attemptId,
            int subQuestionIndex,
            String subQuestion,
            String userText,
            UnansweredReason reason,
            Long bestFaqId,
            Double bestSimilarity,
            UnansweredSource source,
            QuestionEmbedding reusableEmbedding,
            OptionalDouble rejectedThreshold
    ) {
        // 같은 멱등키의 저장도 기존 재시도 생성과 같은 최초 행 잠금을 사용합니다.
        // 동시 재시도가 중복 확인을 함께 통과해서 묶음 건수를 늘리지 않게 합니다.
        lockInitialAttempt(attemptId);
        String safeUserText = userText == null ? "" : userText;
        // 같은 시도의 같은 하위 질문은 한 번만 저장합니다.
        if (unansweredQuestionRepository.existsByAttemptIdAndSubQuestionIndex(attemptId, subQuestionIndex)) {
            return;
        }
        // 재시도가 다시 실패해도 같은 질문이 묶음 건수를 부풀리지 않게 합니다.
        if (unansweredQuestionRepository.existsInSameQuestion(attemptId, subQuestion, safeUserText)) {
            return;
        }

        Long profileId = embeddingProfileService.getCurrentProfileId();
        PGvector vector = reusableEmbedding != null && Objects.equals(subQuestion, reusableEmbedding.question())
                && Objects.equals(profileId, reusableEmbedding.profileId()) && reusableEmbedding.vector() != null
                ? reusableEmbedding.vector()
                : unansweredQuestionVectorRepository.findQuestionEmbedding(subQuestion, profileId)
                        .orElseGet(() -> embeddingService.embedText(subQuestion));

        if (source == UnansweredSource.OUTPUT_GUARD && rejectedThreshold.isPresent()
                && rejectedQuestionDuplicateChecker.check(subQuestion, profileId,
                        new QuestionEmbedding(subQuestion, profileId, vector), rejectedThreshold.getAsDouble())
                        .skipRegistration()) {
            return;
        }

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
                safeUserText,
                vector,
                profileId,
                reason,
                bestFaqId,
                bestSimilarity,
                source
        );

        unansweredQuestionVectorRepository.updateGroupCentroid(
                groupId,
                profileId
        );
    }

    private void lockInitialAttempt(Long attemptId) {
        AnswerAttemptsHistory attempt = answerAttemptsHistoryRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("미응답 질문의 답변 시도 기록이 없습니다."));
        Optional<AnswerAttemptsHistory> initial = attempt.getUserId() == null
                ? answerAttemptsHistoryRepository.findGuestInitialAttemptsForLock(
                        attempt.getConversationId(), attempt.getIdempotencyKey(), 1)
                : answerAttemptsHistoryRepository.findInitialAttemptsForLock(
                        attempt.getUserId(), attempt.getIdempotencyKey(), 1);
        initial.orElseThrow(() -> new IllegalArgumentException("미응답 질문의 최초 시도 기록이 없습니다."));
    }
}
