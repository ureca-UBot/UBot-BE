package com.ubot.faq.retrieval;

import java.util.List;

import com.pgvector.PGvector;
import com.ubot.faq.dto.response.FaqSearchResponseDto;

/** FAQ별 검색 결과와 검색에 사용한 질문 벡터입니다. 유사도 필터 적용 전 값을 유지합니다. */
public record DenseFaqCandidates(List<FaqSearchResponseDto> candidates, PGvector queryVector) {
}
