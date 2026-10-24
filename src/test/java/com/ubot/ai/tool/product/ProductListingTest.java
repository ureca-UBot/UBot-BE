package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductListingTest {

    private static final ProductCard PREMIUM_89 = new ProductCard(ProductType.PLAN, "Ubot 프리미엄 89", 89000, List.of(), List.of());
    private static final ProductCard PREMIUM_99 = new ProductCard(ProductType.PLAN, "Ubot 프리미엄 99", 99000, List.of(), List.of());

    @Test
    void 조건에_맞는_상품을_모두_돌려줬으면_다음_N가지가_있다고_쓴다() {
        var listing = new ProductListing("프리미엄 요금제", "월정액 낮은 순", 2, List.of(PREMIUM_89, PREMIUM_99));

        assertThat(listing.answer()).isEqualTo("""
                프리미엄 요금제는 다음 2가지가 있습니다.
                - Ubot 프리미엄 89 (월 89,000원)
                - Ubot 프리미엄 99 (월 99,000원)""");
    }

    @Test
    void 조건에_맞는_상품이_더_있으면_전체_수와_정렬_기준을_밝힌다() {
        var listing = new ProductListing("조건에 맞는 5G 요금제", "월정액 낮은 순", 12, List.of(PREMIUM_89, PREMIUM_99));

        assertThat(listing.answer()).startsWith("조건에 맞는 5G 요금제는 12가지가 있으며, 월정액 낮은 순으로 2가지를 안내드립니다.\n");
    }

    @Test
    void 상품_종류마다_요금_표기가_다르다() {
        var listing = new ProductListing("결합 상품", "할인액 큰 순", 2, List.of(
                new ProductCard(ProductType.BUNDLE, "Ubot 가족 4인", 20000, List.of(), List.of()),
                new ProductCard(ProductType.ROAMING, "Ubot 로밍 일본 베이직", 11000, List.of(), List.of())));

        assertThat(listing.answer()).isEqualTo("""
                결합 상품은 다음 2가지가 있습니다.
                - Ubot 가족 4인 (월 20,000원 할인)
                - Ubot 로밍 일본 베이직 (일 11,000원)""");
    }

    @Test
    void 주어의_받침에_맞춰_은는을_고른다() {
        assertThat(ProductListing.topicParticle("요금제")).isEqualTo("는");
        assertThat(ProductListing.topicParticle("결합 상품")).isEqualTo("은");
        assertThat(ProductListing.topicParticle("Max")).isEqualTo("는");
    }

    @Test
    void 목록_요청일_때만_주어를_만들고_드러나지_않는_조건이_있으면_조건에_맞는을_붙인다() {
        assertThat(ProductToolResults.listSubject(null, "요금제", false, "프리미엄")).isNull();
        assertThat(ProductToolResults.listSubject(false, "요금제", false, "프리미엄")).isNull();
        assertThat(ProductToolResults.listSubject(true, "요금제", false, "프리미엄")).isEqualTo("프리미엄 요금제");
        assertThat(ProductToolResults.listSubject(true, "요금제", false, "5G", null, "청년")).isEqualTo("5G 청년 요금제");
        assertThat(ProductToolResults.listSubject(true, "요금제", true, "5G")).isEqualTo("조건에 맞는 5G 요금제");
        assertThat(ProductToolResults.listSubject(true, "부가서비스", false, (String) null)).isEqualTo("조건에 맞는 부가서비스");
    }

    @Test
    void 조회가_한_번이고_그_조회가_목록일_때만_목록_답변을_쓴다() {
        var listing = new ProductListing("프리미엄 요금제", "월정액 낮은 순", 2, List.of(PREMIUM_89, PREMIUM_99));

        var once = new ProductDataRecorder();
        once.searched(listing);
        assertThat(once.listing()).contains(listing);

        var twice = new ProductDataRecorder();
        twice.searched(listing);
        twice.searched(null);
        assertThat(twice.listing()).isEmpty();

        var notList = new ProductDataRecorder();
        notList.searched(null);
        assertThat(notList.listing()).isEmpty();
    }
}
