package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanSeriesOverviewTest {

    @Test
    void 시리즈는_월정액이_낮은_순으로_쓰고_시리즈가_아닌_요금제는_뒤에_둔_뒤_안내_문장으로_끝낸다() {
        var overview = new PlanSeriesOverview(List.of(
                new PlanSeriesOverview.Group("Ubot 통화 전용", false, 1, 4900, 4900),
                new PlanSeriesOverview.Group("프리미엄", true, 5, 89000, 129000),
                new PlanSeriesOverview.Group("실속", true, 13, 3300, 19800),
                new PlanSeriesOverview.Group("워치", true, 3, 5500, 5500)));

        assertThat(overview.answer()).isEqualTo("""
                Ubot 요금제는 22가지이며, 다음 종류가 있습니다.
                - 실속 (13가지, 월 3,300원~19,800원)
                - 워치 (3가지, 월 5,500원)
                - 프리미엄 (5가지, 월 89,000원~129,000원)
                - Ubot 통화 전용 (월 4,900원)

                """ + PlanSeriesOverview.GUIDE);
        assertThat(overview.products()).isEmpty();
    }
}
