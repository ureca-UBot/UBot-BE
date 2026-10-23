package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards;
import com.ubot.ai.dto.ProductDataCards.ProductCard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 상품 조회 도구가 돌려준 상품 중 답변에 실제로 쓰인 상품만 화면용 카드로 만듭니다.
 * 도구는 조건에 맞는 상품을 최대 5개 돌려주지만 답변은 그중 일부만 말할 수 있어, 답변에 이름이 나온 상품만 고릅니다.
 * 이름 비교는 ProductNames 규칙("Ubot " 접두어·공백 무시, 긴 이름 우선)을 따릅니다.
 */
public final class ProductDataCardSelector {

    private ProductDataCardSelector() {
    }

    /** 고른 카드가 없으면 응답에 싣지 않도록 null을 돌려줍니다. */
    public static ProductDataCards select(String answer, List<ProductCard> recorded) {
        if (!StringUtils.hasText(answer) || recorded == null || recorded.isEmpty()) {
            return null;
        }
        // 같은 상품이 여러 번의 도구 호출에서 나오면 하나만 남깁니다. 상품 이름은 종류 안에서 겹치지 않습니다.
        Map<String, ProductCard> unique = new LinkedHashMap<>();
        recorded.forEach(card -> unique.putIfAbsent(card.type() + "|" + ProductNames.normalize(card.name()), card));
        List<ProductCard> mentioned = ProductNames.findMentioned(answer, List.copyOf(unique.values()), ProductCard::name);
        if (mentioned.isEmpty()) {
            return null;
        }
        // 도구가 돌려준 순서(정렬 기준)를 유지합니다.
        List<ProductCard> ordered = unique.values().stream().filter(mentioned::contains).toList();
        return new ProductDataCards(ordered);
    }
}
