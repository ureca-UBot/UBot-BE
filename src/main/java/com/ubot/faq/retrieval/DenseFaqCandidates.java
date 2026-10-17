package com.ubot.faq.retrieval;

import java.util.List;

import com.ubot.faq.dto.response.FaqSearchResponseDto;

/** FAQ별 한 건씩 코사인 유사도 내림차순으로 검색한 결과입니다. 필터·리랭커 적용 전 값을 유지합니다. */
public record DenseFaqCandidates(List<FaqSearchResponseDto> candidates, long searchMillis) {
}
