package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.ai.dto.ProductDataCards;
import com.ubot.ai.dto.ProductDataCards.ProductCard;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductDataCardSelectorTest {

    private static final ProductCard COLORING = new ProductCard(ProductType.ADDON, "Ubot 컬러링", 990, "통화연결음 변경");
    private static final ProductCard FORWARDING = new ProductCard(ProductType.ADDON, "Ubot 착신전환", 1100, "국내 번호로 전환");
    private static final ProductCard BOOKPASS = new ProductCard(ProductType.ADDON, "Ubot 북패스", 4900, "전자책");
    private static final ProductCard BOOKPASS_PLAN = new ProductCard(ProductType.PLAN, "Ubot 북패스 7GB+", 16300, null);

    @Test
    void selectsOnlyProductsNamedInAnswer() {
        ProductDataCards cards = ProductDataCardSelector.select(
                "컬러링은 월 990원입니다.", List.of(COLORING, FORWARDING));

        assertThat(cards.products()).containsExactly(COLORING);
    }

    @Test
    void keepsToolOrderAndRemovesDuplicatesFromRepeatedCalls() {
        ProductDataCards cards = ProductDataCardSelector.select(
                "착신전환(1,100원)과 컬러링(990원)이 있습니다.", List.of(COLORING, FORWARDING, COLORING));

        assertThat(cards.products()).containsExactly(COLORING, FORWARDING);
    }

    @Test
    void longerPlanNameIsNotCountedAsShorterAddon() {
        ProductDataCards cards = ProductDataCardSelector.select(
                "북패스 7GB+는 월 16,300원입니다.", List.of(BOOKPASS, BOOKPASS_PLAN));

        assertThat(cards.products()).containsExactly(BOOKPASS_PLAN);
    }

    @Test
    void returnsNullWhenNothingIsMentionedOrRecorded() {
        assertThat(ProductDataCardSelector.select("조건에 맞는 상품이 없습니다.", List.of(COLORING))).isNull();
        assertThat(ProductDataCardSelector.select("컬러링은 월 990원입니다.", List.of())).isNull();
        assertThat(ProductDataCardSelector.select(" ", List.of(COLORING))).isNull();
    }
}
