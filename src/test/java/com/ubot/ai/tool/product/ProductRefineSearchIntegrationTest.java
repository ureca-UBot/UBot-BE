package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.ai.tool.product.ProductRefine.Group;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 버튼으로 고른 조건의 상품 조회가 실제 DB에서 상한·선택지·조건 검사를 지키는지 확인합니다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@Transactional
@DisplayName("버튼 조건 상품 조회 통합 테스트")
class ProductRefineSearchIntegrationTest {

    @Autowired
    private ProductRefineSearch productRefineSearch;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 다른 테스트와 시드 상품에 섞이지 않게 이름에 붙이는 고유 표식입니다.
    private String mark;

    @BeforeEach
    void setUp() {
        mark = "버튼" + UUID.randomUUID().toString().substring(0, 6);
        // 5G 일반 요금제 12개: 1~3은 데이터 5GB, 4~12는 20GB입니다.
        for (int index = 1; index <= 12; index++) {
            insertPlan("Ubot " + mark + " " + index, index * 10000, index <= 3 ? 5000L : 20000L, false);
        }
    }

    @Test
    @DisplayName("검색어만 있으면 10건까지 돌려주고, 더 있으면 검색어를 유지한 선택지를 붙인다")
    void keywordOnlyReturnsTenAndRefine() {
        ProductRefineSearch.Result result = productRefineSearch.search(ProductType.PLAN, mark, Map.of());

        assertThat(result.productData().products()).hasSize(10);
        assertThat(result.answer()).contains("12가지가 있으며", "10가지를 안내드립니다");
        assertThat(result.productData().refine()).isNotNull()
                .satisfies(refine -> {
                    assertThat(refine.keyword()).isEqualTo(mark);
                    assertThat(refine.total()).isEqualTo(12);
                });
    }

    @Test
    @DisplayName("검색어와 고른 조건을 함께 걸고, 결과가 다 보이면 선택지를 붙이지 않는다")
    void keywordAndConditionCombine() {
        ProductRefineSearch.Result result = productRefineSearch.search(ProductType.PLAN, mark,
                Map.of(ProductRefine.DATA, "10"));

        assertThat(result.productData().products()).hasSize(9)
                .allSatisfy(card -> assertThat(card.name()).contains(mark));
        assertThat(result.answer()).contains("다음 9가지가 있습니다");
        assertThat(result.productData().refine()).isNull();
    }

    @Test
    @DisplayName("결과가 하나여도 목록 답변으로 답한다")
    void singleResultIsListed() {
        ProductRefineSearch.Result result = productRefineSearch.search(ProductType.PLAN, mark + " 12", Map.of());

        assertThat(result.productData().products()).singleElement()
                .satisfies(card -> assertThat(card.name()).isEqualTo("Ubot " + mark + " 12"));
        assertThat(result.answer()).contains("다음 1가지가 있습니다");
    }

    @Test
    @DisplayName("무제한만을 고르면 무제한 요금제만 찾는다")
    void unlimitedOnly() {
        insertPlan("Ubot " + mark + " 무제한", 99000, null, true);

        ProductRefineSearch.Result result = productRefineSearch.search(ProductType.PLAN, mark,
                Map.of(ProductRefine.DATA, ProductRefine.DATA_UNLIMITED));

        assertThat(result.productData().products()).singleElement()
                .satisfies(card -> assertThat(card.name()).endsWith("무제한"));
    }

    @Test
    @DisplayName("0건이면 고른 값이 선택된 선택지와 다시 고르라는 답변을 돌려준다")
    void emptyResultKeepsSelection() {
        ProductRefineSearch.Result result = productRefineSearch.search(ProductType.PLAN, mark,
                Map.of(ProductRefine.NETWORK, "LTE"));

        assertThat(result.answer()).isEqualTo("조건에 맞는 요금제가 없습니다. 조건을 바꿔 다시 골라 주세요.");
        assertThat(result.productData().products()).isEmpty();
        Group network = result.productData().refine().groups().getFirst();
        assertThat(network.key()).isEqualTo(ProductRefine.NETWORK);
        assertThat(network.selected()).isEqualTo("LTE");
        assertThat(result.productData().refine().keyword()).isEqualTo(mark);
    }

    @Test
    @DisplayName("부가서비스는 분류로 거른다")
    void addonByCategory() {
        insertAddon("Ubot " + mark + " 착신", "CALL");
        insertAddon("Ubot " + mark + " 대기", "CALL");
        insertAddon("Ubot " + mark + " 스팸", "BLOCKING");

        ProductRefineSearch.Result result = productRefineSearch.search(ProductType.ADDON, mark,
                Map.of(ProductRefine.CATEGORY, "CALL"));

        assertThat(result.productData().products()).hasSize(2)
                .noneSatisfy(card -> assertThat(card.name()).contains("스팸"));
    }

    @Test
    @DisplayName("그 종류에 없는 키이거나 해석할 수 없는 값이면 조회하지 않고 거절한다")
    void rejectsUnknownKeysAndValues() {
        assertThatThrownBy(() -> productRefineSearch.search(ProductType.PLAN, mark, Map.of("foo", "1")))
                .isInstanceOf(ProductRefineSearch.InvalidConditionException.class);
        assertThatThrownBy(() -> productRefineSearch.search(ProductType.ADDON, mark, Map.of(ProductRefine.NETWORK, "LTE")))
                .isInstanceOf(ProductRefineSearch.InvalidConditionException.class);
        assertThatThrownBy(() -> productRefineSearch.search(ProductType.PLAN, mark, Map.of(ProductRefine.NETWORK, "6G")))
                .isInstanceOf(ProductRefineSearch.InvalidConditionException.class);
        assertThatThrownBy(() -> productRefineSearch.search(ProductType.PLAN, mark, Map.of(ProductRefine.MAX_FEE, "abc")))
                .isInstanceOf(ProductRefineSearch.InvalidConditionException.class);
        assertThatThrownBy(() -> productRefineSearch.search(ProductType.ROAMING, null, Map.of(ProductRefine.COUNTRY, "유럽")))
                .isInstanceOf(ProductRefineSearch.InvalidConditionException.class);
    }

    @Test
    @DisplayName("단계별 선택지는 앞 단계까지 고른 값에 더했을 때 결과가 있는지만 알려 준다")
    void optionsTellWhetherEachChoiceHasResults() {
        ProductRefine.Availability network = productRefineSearch.options(ProductType.PLAN, mark, Map.of(),
                ProductRefine.NETWORK);

        assertThat(network.anyAvailable()).isTrue();
        assertThat(network.options()).containsExactly(
                new ProductRefine.OptionAvailability("FIVE_G", true), new ProductRefine.OptionAvailability("LTE", false));

        // 5G를 고른 뒤 데이터 단계입니다. 이 요금제들은 데이터가 최대 20GB입니다.
        ProductRefine.Availability data = productRefineSearch.options(ProductType.PLAN, mark,
                Map.of(ProductRefine.NETWORK, "FIVE_G"), ProductRefine.DATA);

        assertThat(data.options()).containsExactly(
                new ProductRefine.OptionAvailability("10", true), new ProductRefine.OptionAvailability("50", false),
                new ProductRefine.OptionAvailability("100", false),
                new ProductRefine.OptionAvailability(ProductRefine.DATA_UNLIMITED, false));
    }

    @Test
    @DisplayName("앞 단계 조합이 이미 0건이면 모든 선택지와 상관없음이 불가능하고, 정렬은 결과 수와 상관없이 따른다")
    void optionsWhenEarlierStepsHaveNoResults() {
        ProductRefine.Availability target = productRefineSearch.options(ProductType.PLAN, mark,
                Map.of(ProductRefine.NETWORK, "LTE"), ProductRefine.TARGET);
        assertThat(target.anyAvailable()).isFalse();
        assertThat(target.options()).noneMatch(ProductRefine.OptionAvailability::available);

        ProductRefine.Availability sort = productRefineSearch.options(ProductType.PLAN, mark,
                Map.of(ProductRefine.NETWORK, "FIVE_G"), ProductRefine.SORT);
        assertThat(sort.options()).allMatch(ProductRefine.OptionAvailability::available);
    }

    @Test
    @DisplayName("고를 단계가 아니거나 이미 고른 단계면 거절한다")
    void optionsRejectWrongKeys() {
        assertThatThrownBy(() -> productRefineSearch.options(ProductType.PLAN, mark, Map.of(), ProductRefine.CATEGORY))
                .isInstanceOf(ProductRefineSearch.InvalidConditionException.class);
        assertThatThrownBy(() -> productRefineSearch.options(ProductType.PLAN, mark,
                Map.of(ProductRefine.NETWORK, "FIVE_G"), ProductRefine.NETWORK))
                .isInstanceOf(ProductRefineSearch.InvalidConditionException.class);
        assertThatThrownBy(() -> productRefineSearch.options(ProductType.PLAN, mark,
                Map.of(ProductRefine.NETWORK, "6G"), ProductRefine.TARGET))
                .isInstanceOf(ProductRefineSearch.InvalidConditionException.class);
    }

    private void insertPlan(String name, int fee, Long dataMb, boolean unlimited) {
        jdbcTemplate.update("""
                INSERT INTO plans (plan_code, name, network_type, target_group, monthly_fee, data_amount_mb,
                    data_unlimited, voice_unlimited, sms_unlimited, tethering_amount_mb, description)
                VALUES (?, ?, 'FIVE_G', 'GENERAL', ?, ?, ?, TRUE, TRUE, 0, NULL)
                """, UUID.randomUUID().toString(), name, fee, dataMb, unlimited);
    }

    private void insertAddon(String name, String category) {
        jdbcTemplate.update("INSERT INTO addon_services (code, name, monthly_fee, description, category) VALUES (?, ?, 0, NULL, ?)",
                UUID.randomUUID().toString(), name, category);
    }
}
