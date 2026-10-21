package com.ubot.unanswered.service;

import com.pgvector.PGvector;
import com.ubot.chat.service.QuestionRequirementMatcher;
import com.ubot.embedding.client.EmbeddingClient;
import com.ubot.unanswered.repository.UnansweredQuestionVectorRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FAQ 보완 후보의 관리자 재등록 생략 여부만 판단합니다. 질문·답변·저장·묶음 상태를 바꾸지 않습니다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RejectedQuestionDuplicateChecker {

    private static final int MAX_REJECTED_GROUPS = 20;

    private final UnansweredQuestionVectorRepository vectorRepository;
    private final QuestionRequirementMatcher requirementMatcher;

    /**
     * 입력 가드를 통과한 하위 질문과 기존 검색에서 확정한 프로필을 받습니다. 프로필 생성·임베딩 호출은 하지 않습니다.
     * rejectedThreshold는 반려 전용 기준이며 FAQ 검색·대기 묶기 설정에서 가져오지 않습니다.
     * vector가 있어도 문장과 프로필이 정확히 같지 않으면 재사용하지 않습니다.
     */
    @Transactional(readOnly = true)
    public Decision check(String question, Long profileId, QuestionEmbedding reusableEmbedding, double rejectedThreshold) {
        if (!Double.isFinite(rejectedThreshold) || rejectedThreshold <= 0 || rejectedThreshold > 1) {
            throw new IllegalArgumentException("반려 전용 유사도 기준은 0보다 크고 1 이하여야 합니다.");
        }
        if (profileId == null || profileId <= 0) return keep(Reason.UNDETERMINED, VectorSource.NONE);
        Optional<List<String>> requirement = requirementMatcher.signature(question);
        if (requirement.isEmpty()) return keep(Reason.UNDETERMINED, VectorSource.NONE);

        VectorSource vectorSource = VectorSource.NONE;
        try {
            PGvector vector;
            if (reusableEmbedding != null && Objects.equals(question, reusableEmbedding.question())
                    && Objects.equals(profileId, reusableEmbedding.profileId()) && usable(reusableEmbedding.vector())) {
                vector = reusableEmbedding.vector();
                vectorSource = VectorSource.CURRENT_REQUEST;
            } else {
                vector = vectorRepository.findQuestionEmbedding(question, profileId).orElse(null);
                if (vector != null) vectorSource = VectorSource.STORED_QUESTION;
            }
            if (!usable(vector)) {
                return keep(Reason.VECTOR_REQUIRED, VectorSource.NONE);
            }
            var groups = vectorRepository.findRejectedGroups(vector, profileId, rejectedThreshold, MAX_REJECTED_GROUPS);
            for (var group : groups) {
                if (!Double.isFinite(group.similarity()) || group.similarity() < rejectedThreshold) continue;
                Optional<List<String>> rejectedRequirement = requirementMatcher.signature(group.question());
                if (rejectedRequirement.isPresent() && requirement.get().equals(rejectedRequirement.get())) {
                    return new Decision(true, group.groupId(), Reason.SAME_REJECTED_REQUIREMENT, vectorSource);
                }
            }
            return keep(groups.isEmpty() ? Reason.NO_REJECTED_MATCH : Reason.UNDETERMINED, vectorSource);
        } catch (DataAccessException exception) {
            // 반려 조회가 실패했다고 사용자 답변을 중단하거나 FAQ 부족 후보를 잃지 않습니다.
            log.warn("반려 기록을 확인하지 못해 검토 후보를 유지합니다: {}", exception.getClass().getSimpleName());
            return keep(Reason.CHECK_UNAVAILABLE, vectorSource);
        }
    }

    private Decision keep(Reason reason, VectorSource vectorSource) {
        return new Decision(false, null, reason, vectorSource);
    }

    private boolean usable(PGvector vector) {
        if (vector == null) return false;
        float[] values = vector.toArray();
        if (values == null || values.length != EmbeddingClient.DIMENSIONS) return false;
        boolean nonzero = false;
        for (float value : values) {
            if (!Float.isFinite(value)) return false;
            nonzero |= value != 0;
        }
        return nonzero;
    }

    /** 이 문장으로 이 프로필에서 만든 벡터여야 합니다. 전체 질문 벡터를 다른 하위 질문에 붙이지 않습니다. */
    public record QuestionEmbedding(String question, Long profileId, PGvector vector) {
    }

    /** skipRegistration은 관리자 후보 재등록에만 적용하며 사용자 요청의 차단 여부가 아닙니다. */
    public record Decision(boolean skipRegistration, Long rejectedGroupId, Reason reason, VectorSource vectorSource) {
    }

    public enum Reason {
        SAME_REJECTED_REQUIREMENT, NO_REJECTED_MATCH, UNDETERMINED, VECTOR_REQUIRED, CHECK_UNAVAILABLE
    }

    public enum VectorSource {
        NONE, CURRENT_REQUEST, STORED_QUESTION
    }
}
