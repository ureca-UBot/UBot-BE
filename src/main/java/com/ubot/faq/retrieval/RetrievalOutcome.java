package com.ubot.faq.retrieval;

import java.util.List;
import com.ubot.faq.dto.response.FaqSearchResponseDto;

/** 답변에 사용할 벡터 검색 상위 후보와 미응답 기록용 1위 FAQ입니다. */
public record RetrievalOutcome(List<FaqSearchResponseDto> rankedCandidates, FaqSearchResponseDto denseTop1) {
}
