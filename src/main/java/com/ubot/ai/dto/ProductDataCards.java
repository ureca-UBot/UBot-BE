package com.ubot.ai.dto;

import com.ubot.ai.tool.ProductType;
import java.util.List;

/**
 * 답변에 쓰인 상품(요금제·부가서비스·결합·로밍)을 화면이 카드로 그릴 수 있게 담습니다.
 * LLM 문장과 별도로 응답에 실리며, 목록은 비어 있을 수는 있어도 null이 아닙니다.
 */
public record ProductDataCards(List<ProductCard> products) {

    public ProductDataCards {
        products = products == null ? List.of() : List.copyOf(products);
    }

    /**
     * 화면에 보여 줄 정보는 이름·요금·설명뿐입니다. type은 화면이 요금 앞말(월·일·할인)을 고르는 데만 씁니다.
     * fee는 요금제·부가서비스는 월 요금, 로밍은 일 요금, 결합은 할인액이며 없으면 null입니다.
     */
    public record ProductCard(ProductType type, String name, Integer fee, String description) {
    }
}
