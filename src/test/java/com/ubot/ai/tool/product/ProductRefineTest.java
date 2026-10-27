package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.ai.tool.product.ProductDataRecorder.SearchRecord;
import com.ubot.ai.tool.product.ProductRefine.Group;
import com.ubot.ai.tool.product.ProductRefine.Option;
import com.ubot.bundle.enums.BundleType;
import com.ubot.plan.enums.NetworkType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("상품 목록 조건 선택지")
class ProductRefineTest {

    @Test
    @DisplayName("요금제는 적용된 값을 선택해 두고, 이름은 묶음이 아니라 검색어로 둔다")
    void planGroupsSelectAppliedValuesAndKeepNameAsKeyword() {
        ProductRefine refine = ProductRefine.of(ProductSearch.of(ProductType.PLAN, "밸런스",
                ProductRefine.NETWORK, NetworkType.FIVE_G, ProductRefine.DATA, 10), 11);

        assertThat(refine.keyword()).isEqualTo("밸런스");
        assertThat(refine.total()).isEqualTo(11);
        assertThat(refine.groups()).extracting(Group::key).containsExactly(
                ProductRefine.NETWORK, ProductRefine.TARGET, ProductRefine.MAX_FEE, ProductRefine.DATA, ProductRefine.SORT);
        assertThat(group(refine, ProductRefine.NETWORK).selected()).isEqualTo("FIVE_G");
        assertThat(group(refine, ProductRefine.NETWORK).options()).extracting(Option::label).containsExactly("5G", "LTE");
        assertThat(group(refine, ProductRefine.TARGET).selected()).isNull();
        assertThat(group(refine, ProductRefine.DATA).selected()).isEqualTo("10");
        assertThat(group(refine, ProductRefine.DATA).options()).extracting(Option::label)
                .containsExactly("10GB 이상", "50GB 이상", "100GB 이상", "무제한만");
        assertThat(group(refine, ProductRefine.MAX_FEE).options()).extracting(Option::label)
                .containsExactly("3만원 이하", "5만원 이하", "7만원 이하", "10만원 이하");
    }

    @Test
    @DisplayName("상관없음은 정렬·부가서비스 분류·로밍 나라에만 없고, 정렬은 기본값이 선택돼 있다")
    void anyIsMissingOnlyForSortAddonCategoryAndRoamingCountry() {
        ProductRefine plan = ProductRefine.of(ProductSearch.of(ProductType.PLAN, null), 107);
        assertThat(plan.groups()).filteredOn(group -> !group.anyAllowed()).extracting(Group::key)
                .containsExactly(ProductRefine.SORT);
        assertThat(group(plan, ProductRefine.SORT).selected()).isEqualTo(PlanSort.MONTHLY_FEE_ASC.name());

        ProductRefine addon = ProductRefine.of(ProductSearch.of(ProductType.ADDON, null), 21);
        assertThat(addon.groups()).singleElement().satisfies(group -> {
            assertThat(group.anyAllowed()).isFalse();
            assertThat(group.options()).extracting(Option::label).contains("통화", "차단", "데이터 충전");
        });

        ProductRefine roaming = ProductRefine.of(ProductSearch.withCountries(ProductType.ROAMING, null,
                List.of("미국", "일본")), 48);
        assertThat(roaming.groups()).singleElement().satisfies(group -> {
            assertThat(group.anyAllowed()).isFalse();
            assertThat(group.options()).extracting(Option::value).containsExactly("미국", "일본");
        });

        ProductRefine bundle = ProductRefine.of(ProductSearch.of(ProductType.BUNDLE, null), 16);
        assertThat(bundle.groups()).allMatch(Group::anyAllowed);
    }

    @Test
    @DisplayName("적용된 값이 정해진 구간에 없으면 그 값을 선택지에 더해 다음 요청에서 빠지지 않게 한다")
    void appliedValueOutsideFixedOptionsIsAdded() {
        ProductRefine plan = ProductRefine.of(ProductSearch.of(ProductType.PLAN, null,
                ProductRefine.MAX_FEE, 60000, ProductRefine.DATA, 20), 30);
        assertThat(group(plan, ProductRefine.MAX_FEE).options()).last()
                .isEqualTo(new Option("60000", "6만원 이하"));
        assertThat(group(plan, ProductRefine.DATA).options()).last().isEqualTo(new Option("20", "20GB 이상"));
        assertThat(ProductRefine.feeLabel("65000")).isEqualTo("65,000원 이하");

        ProductRefine bundle = ProductRefine.of(ProductSearch.of(ProductType.BUNDLE, null,
                ProductRefine.BUNDLE_TYPE, BundleType.HOME, ProductRefine.LINE_COUNT, 1), 0);
        assertThat(group(bundle, ProductRefine.BUNDLE_TYPE).selected()).isEqualTo("HOME");
        assertThat(group(bundle, ProductRefine.LINE_COUNT).options()).last().isEqualTo(new Option("1", "1회선"));

        ProductRefine roaming = ProductRefine.of(ProductSearch.withCountries(ProductType.ROAMING, null,
                List.of("미국", "일본", "중국"), ProductRefine.COUNTRY, "일본, 중국"), 6);
        assertThat(group(roaming, ProductRefine.COUNTRY).selected()).isEqualTo("일본, 중국");
        assertThat(group(roaming, ProductRefine.COUNTRY).options()).hasSize(4);
    }

    @Test
    @DisplayName("선택지는 조회가 한 번이고 결과가 상한을 넘거나 0건일 때만 붙는다")
    void refineOnlyWhenResultsOverflowOrAreEmpty() {
        ProductSearch search = ProductSearch.of(ProductType.PLAN, "밸런스");

        assertThat(recorded(new ProductDataRecorder(), search, 11).refine()).isPresent();
        assertThat(recorded(new ProductDataRecorder(), search, 10).refine()).isEmpty();
        assertThat(recorded(new ProductDataRecorder(), search, 0).refine()).isPresent();
        assertThat(recorded(ProductDataRecorder.forRefine(10), search, 9).refine()).isEmpty();
        assertThat(recorded(ProductDataRecorder.forRefine(10), search, 11).refine()).isPresent();

        ProductDataRecorder twice = recorded(new ProductDataRecorder(), search, 12);
        twice.searched(new SearchRecord(search, 12, null));
        assertThat(twice.refine()).isEmpty();
    }

    private static ProductDataRecorder recorded(ProductDataRecorder recorder, ProductSearch search, long total) {
        recorder.searched(new SearchRecord(search, total, null));
        return recorder;
    }

    private static Group group(ProductRefine refine, String key) {
        return refine.groups().stream().filter(group -> group.key().equals(key)).findFirst().orElseThrow();
    }
}
