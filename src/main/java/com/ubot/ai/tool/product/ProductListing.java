package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import java.util.List;

/**
 * LLM이 목록 요청이라고 표시한(listRequested) 조회에서 상품이 2개 이상 나왔을 때의 결과입니다.
 * AiService는 이 결과로 LLM 답변 대신 목록 답변을 만듭니다. 작은 모델은 결과 5개를 받고도 한두 개만 쓰거나
 * 도구 안내 문구를 "정보가 부족하다"로 읽는 일이 있어, 목록 답변은 형식과 상품 수를 코드가 정합니다.
 *
 * @param subject  답변의 주어. 예: "프리미엄 요금제", "조건에 맞는 요금제"
 * @param order    도구가 정렬한 순서. 예: "월정액 낮은 순"
 * @param total    조건에 맞는 전체 상품 수
 * @param products 돌려준 상품. total보다 적을 수 있습니다.
 */
public record ProductListing(String subject, String order, long total, List<ProductCard> products) {

    public ProductListing {
        products = List.copyOf(products);
    }

    /**
     * "프리미엄 요금제는 다음 5가지가 있습니다." 뒤에 상품을 한 줄씩 씁니다.
     * 돌려준 상품보다 조건에 맞는 상품이 많으면 전체 수와 정렬 기준을 함께 밝힙니다.
     */
    public String answer() {
        StringBuilder text = new StringBuilder(subject).append(topicParticle(subject)).append(' ');
        if (total > products.size()) {
            text.append(total).append("가지가 있으며, ").append(order).append("으로 ")
                    .append(products.size()).append("가지를 안내드립니다.");
        } else {
            text.append("다음 ").append(products.size()).append("가지가 있습니다.");
        }
        for (ProductCard product : products) {
            text.append("\n- ").append(product.name()).append(" (").append(fee(product)).append(')');
        }
        return text.toString();
    }

    private static String fee(ProductCard product) {
        String amount = ToolFormats.won(product.fee());
        return switch (product.type()) {
            case PLAN, ADDON -> "월 " + amount;
            case BUNDLE -> "월 " + amount + " 할인";
            case ROAMING -> "일 " + amount;
        };
    }

    /** 받침이 있으면 "은", 없으면 "는"입니다. 한글이 아니면 받침이 없는 것으로 봅니다. */
    static String topicParticle(String word) {
        char last = word.charAt(word.length() - 1);
        boolean hangul = last >= '가' && last <= '힣';
        return hangul && (last - '가') % 28 != 0 ? "은" : "는";
    }
}
