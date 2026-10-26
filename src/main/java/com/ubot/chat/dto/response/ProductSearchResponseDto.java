package com.ubot.chat.dto.response;

import com.ubot.ai.dto.ProductDataCards;

// answer는 코드가 만든 목록 답변 문장이고, productData는 카드와(결과가 넘치거나 0건이면) 다음 조건 선택지입니다.
public record ProductSearchResponseDto(String answer, ProductDataCards productData) {
}
