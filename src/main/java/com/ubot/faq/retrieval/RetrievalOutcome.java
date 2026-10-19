package com.ubot.faq.retrieval;

import java.util.List;

import com.ubot.faq.dto.response.FaqSearchResponseDto;

/**
 * FAQ 검색 결과입니다. 유사도 기준 적용과 최종 개수 제한은 호출한 쪽(ChatAnswerProcessor)이 합니다.
 *
 * @param rankedCandidates 답변에 쓸 순서대로 놓인 후보. Reranker를 썼으면 다시 정렬한 후보 전체이고,
 *                         아니면 벡터 검색 상위 후보입니다. similarityScore는 항상 코사인 유사도입니다.
 * @param denseTop1 벡터 검색 1위 FAQ. 후보가 없으면 null입니다. 미응답 질문 기록에 씁니다.
 * @param reranked Reranker로 다시 정렬했는지 여부
 * @param fallbackReason Reranker를 쓰지 못해 벡터 검색 결과로 돌아온 사유. 그런 일이 없었으면 null입니다.
 */
public record RetrievalOutcome(
        List<FaqSearchResponseDto> rankedCandidates,
        FaqSearchResponseDto denseTop1,
        boolean reranked,
        String fallbackReason
) {

    static RetrievalOutcome dense(List<FaqSearchResponseDto> candidates) {
        return new RetrievalOutcome(candidates, firstOf(candidates), false, null);
    }

    static RetrievalOutcome reranked(List<FaqSearchResponseDto> reordered, FaqSearchResponseDto denseTop1) {
        return new RetrievalOutcome(reordered, denseTop1, true, null);
    }

    static RetrievalOutcome fallback(List<FaqSearchResponseDto> denseTop, FaqSearchResponseDto denseTop1, String reason) {
        return new RetrievalOutcome(denseTop, denseTop1, false, reason);
    }

    private static FaqSearchResponseDto firstOf(List<FaqSearchResponseDto> candidates) {
        return candidates == null || candidates.isEmpty() ? null : candidates.get(0);
    }
}
