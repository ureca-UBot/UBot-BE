package com.ubot.chat.dto.request;

import com.ubot.ai.tool.product.ProductType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

// 상품 목록 답변의 조건 선택지(refine)에서 고른 값으로 다시 조회합니다.
// keyword는 응답의 refine.keyword(검색어 칩)이고, 사용자가 칩을 빼면 보내지 않습니다.
// conditions는 refine.groups의 key별로 고른 option.value이며, "상관없음"인 조건은 넣지 않습니다.
public record ProductSearchRequestDto(
        @NotNull ProductType type,
        @Size(max = 50) String keyword,
        @Size(max = 10) Map<String, @Size(max = 50) String> conditions) {
}
