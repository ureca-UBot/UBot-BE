package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import java.util.List;

/**
 * 목록 요청 결과가 다 들어가지 않을 때 첫 답변으로 쓰는 안내입니다. 상품을 나열하지 않고 전체 개수만 밝힌 뒤
 * 조건을 고르라고 안내하며, 화면은 함께 실린 조건 선택지(ProductRefine)를 보여 줍니다. 카드는 붙이지 않습니다.
 * 조건 없이 요금제 종류를 물은 경우는 시리즈 개요(PlanSeriesOverview)의 개수·시리즈 부분을 그대로 남깁니다.
 */
public record ProductRefinePrompt(String answer) implements ProductListAnswer {

    static final String GUIDE = "원하시는 조건을 선택해 주세요.";

    /** subject는 목록 답변의 주어입니다. 예: "무제한 요금제" → "무제한 요금제는 15가지가 있습니다." */
    static ProductRefinePrompt of(String subject, long total) {
        return new ProductRefinePrompt(subject + ProductListing.topicParticle(subject) + " " + total + "가지가 있습니다.\n" + GUIDE);
    }

    static ProductRefinePrompt of(ProductListAnswer base, long total) {
        return switch (base) {
            case ProductListing listing -> of(listing.subject(), total);
            case PlanSeriesOverview overview -> new ProductRefinePrompt(overview.summary() + "\n\n" + GUIDE);
            case ProductRefinePrompt prompt -> prompt;
        };
    }

    @Override
    public List<ProductCard> products() {
        return List.of();
    }
}
