package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProductNameIndexTest {

    @Test
    void 두_요금제_이상이_같이_쓰는_첫_단어만_시리즈로_본다() {
        List<String> plans = List.of("Ubot 프리미엄 89", "Ubot 프리미엄 Max", "Ubot 데이터 5GB", "Ubot 데이터 MAX",
                "Ubot 통화 전용", "이름형식다름");

        assertThat(ProductNameIndex.planSeries(plans)).containsExactly("데이터", "프리미엄");
    }

    @Test
    void 시리즈_이름_바로_뒤에_요금제가_올_때만_찾는다() {
        List<String> series = List.of("데이터", "프리미엄", "밸런스");

        assertThat(ProductNameIndex.planSeriesIn("프리미엄 요금제 종류 알려줘", series)).containsExactly("프리미엄");
        assertThat(ProductNameIndex.planSeriesIn("프리미엄요금제 뭐 있어", series)).containsExactly("프리미엄");
        // "데이터 많은 요금제"의 "데이터"는 시리즈가 아니라 일반 단어입니다.
        assertThat(ProductNameIndex.planSeriesIn("데이터 많은 요금제 알려줘", series)).isEmpty();
        assertThat(ProductNameIndex.planSeriesIn("프리미엄 요금제랑 밸런스 요금제 비교", series))
                .containsExactlyInAnyOrder("프리미엄", "밸런스");
        assertThat(ProductNameIndex.planSeriesIn(null, series)).isEmpty();
    }
}
