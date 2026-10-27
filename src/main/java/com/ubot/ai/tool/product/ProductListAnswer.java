package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import java.util.List;

/**
 * LLM 답변 대신 코드가 만드는 상품 목록 답변입니다. AiService가 상품 도구만 붙은 단일 질문에서 조회가 한 번이었을 때 씁니다.
 * 조회한 상품을 나열하는 ProductListing과, 조건 없이 요금제 전체를 물었을 때 시리즈별로 묶어 보여 주는 PlanSeriesOverview,
 * 결과가 다 들어가지 않아 목록 대신 조건을 고르라고 안내하는 ProductRefinePrompt가 있습니다.
 */
public sealed interface ProductListAnswer permits ProductListing, PlanSeriesOverview, ProductRefinePrompt {

    String answer();

    /** 화면 카드로 붙일 상품입니다. 특정 상품을 고르지 않은 답변이면 비어 있습니다. */
    List<ProductCard> products();
}
