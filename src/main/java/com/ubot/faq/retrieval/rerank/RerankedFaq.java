package com.ubot.faq.retrieval.rerank;

import com.ubot.faq.dto.response.FaqSearchResponseDto;

/**
 * Reranker가 점수를 매긴 FAQ 후보입니다.
 *
 * @param faq 벡터 검색이 돌려준 FAQ 그대로입니다. similarityScore는 코사인 유사도이고 Reranker 점수로 바꾸지 않습니다.
 * @param rerankScore Reranker 점수. 높을수록 질문과 관련이 깊습니다. 코사인 유사도와 척도가 다릅니다.
 * @param denseRank 벡터 검색에서의 순위(1부터)
 */
public record RerankedFaq(FaqSearchResponseDto faq, double rerankScore, int denseRank) {
}
