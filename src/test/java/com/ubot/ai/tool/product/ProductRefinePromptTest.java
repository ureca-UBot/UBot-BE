package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("조건 선택 안내 답변")
class ProductRefinePromptTest {

    @Test
    @DisplayName("목록이었으면 상품을 나열하지 않고 전체 개수와 조건 선택 안내만 쓴다")
    void listingBecomesCountAndGuide() {
        var listing = new ProductListing("밸런스 요금제", "월정액 낮은 순", 11, List.of());
        var bundles = new ProductListing("가족 결합 상품", "할인액 큰 순", 6, List.of());

        assertThat(ProductRefinePrompt.of(listing, 11).answer())
                .isEqualTo("밸런스 요금제는 11가지가 있습니다.\n원하시는 조건을 선택해 주세요.");
        assertThat(ProductRefinePrompt.of(bundles, 6).answer()).startsWith("가족 결합 상품은 6가지가 있습니다.");
        assertThat(ProductRefinePrompt.of(listing, 11).products()).isEmpty();
    }

    @Test
    @DisplayName("시리즈 개요였으면 개수·시리즈 부분을 남기고 끝의 안내만 바꾼다")
    void overviewKeepsSummary() {
        var overview = new PlanSeriesOverview(List.of(new PlanSeriesOverview.Group("실속", true, 13, 3300, 19800)));

        String answer = ProductRefinePrompt.of(overview, 13).answer();

        assertThat(answer).startsWith(overview.summary()).endsWith("\n\n" + ProductRefinePrompt.GUIDE)
                .doesNotContain(PlanSeriesOverview.GUIDE);
    }
}
