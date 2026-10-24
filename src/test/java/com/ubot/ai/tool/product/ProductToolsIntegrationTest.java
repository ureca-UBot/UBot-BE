package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.ai.dto.ProductDataCards.ProductCard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 상품 조회 도구 4개가 실제 DB에서 조건·정렬·5건 상한·판매 상태를 지키는지 확인합니다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@Transactional
@DisplayName("상품 조회 도구 통합 테스트")
class ProductToolsIntegrationTest {

    @Autowired
    private PlanTools planTools;
    @Autowired
    private AddonTools addonTools;
    @Autowired
    private BundleTools bundleTools;
    @Autowired
    private RoamingTools roamingTools;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ProductDataRecorder recorder;
    private ToolContext toolContext;
    // 다른 테스트의 상품과 섞이지 않게 이름에 붙이는 고유 표식입니다.
    private String mark;

    @BeforeEach
    void setUp() {
        recorder = new ProductDataRecorder();
        toolContext = new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, recorder));
        mark = "검증" + UUID.randomUUID().toString().substring(0, 6);
    }

    @Test
    @DisplayName("요금제는 기본으로 월정액 낮은 순 5건만 돌려주고, 더 있으면 조건을 좁혀 달라고 안내한다")
    void plansAreLimitedToFiveByMonthlyFee() {
        for (int index = 1; index <= 7; index++) {
            insertPlan(mark + " " + index, "FIVE_G", "GENERAL", 80000 - index * 5000, 10000L * index, false, 1000);
        }

        String result = planTools.searchPlans(mark, null, null, null, null, null, null, null, toolContext);

        assertThat(result).startsWith("조건에 맞는 요금제 7건 중 5건(월정액 낮은 순)");
        assertThat(result.indexOf(mark + " 7")).isLessThan(result.indexOf(mark + " 6"));
        assertThat(result).doesNotContain(mark + " 2 |").doesNotContain(mark + " 1 |")
                .contains(String.format(ProductToolResults.MORE_RESULTS_MESSAGE, "요금제"));
        assertThat(recorder.cards()).hasSize(5).allSatisfy(card -> assertThat(card.type()).isEqualTo(ProductType.PLAN));
    }

    @Test
    @DisplayName("데이터 많은 순은 무제한을 먼저 두고, 월정액 상한과 함께 걸 수 있다")
    void plansSortByDataWithFeeLimit() {
        insertPlan(mark + " 적은", "FIVE_G", "GENERAL", 50000, 5000L, false, 400);
        insertPlan(mark + " 많은", "FIVE_G", "GENERAL", 90000, 150000L, false, 5000);
        insertPlan(mark + " 무제한", "FIVE_G", "GENERAL", 95000, null, true, null);
        insertPlan(mark + " 비싼무제한", "FIVE_G", "GENERAL", 130000, null, true, null);

        String result = planTools.searchPlans(mark, null, null, 100000, null, null, "DATA_DESC", null, toolContext);

        assertThat(result).startsWith("조건에 맞는 요금제 3건 중 3건(데이터 많은 순)").doesNotContain("비싼무제한");
        assertThat(result.indexOf(mark + " 무제한")).isLessThan(result.indexOf(mark + " 많은"));
        assertThat(result.indexOf(mark + " 많은")).isLessThan(result.indexOf(mark + " 적은"));
        assertThat(result).contains("데이터 150GB(소진 후 최대 5Mbps)", "데이터 5GB(소진 후 최대 400Kbps)", "데이터 무제한");
    }

    @Test
    @DisplayName("데이터는 DB 규칙(1GB = 1000MB)대로 거르고 보여 주며, 최소 데이터 조건은 무제한도 포함한다")
    void plansUseDecimalGigabytes() {
        insertPlan(mark + " 일기가", "LTE", "GENERAL", 3900, 1000L, false, null);
        insertPlan(mark + " 오백", "LTE", "GENERAL", 3300, 500L, false, null);
        insertPlan(mark + " 무제한", "FIVE_G", "GENERAL", 70000, null, true, null);

        String result = planTools.searchPlans(mark, null, null, null, 1, null, null, null, toolContext);

        assertThat(result).startsWith("조건에 맞는 요금제 2건 중 2건").doesNotContain(mark + " 오백")
                .contains("데이터 1GB(소진 후 차단)", "데이터 무제한");
    }

    @Test
    @DisplayName("요금제 행에는 설정의 할인율과 선택약정 할인 금액을 함께 넣는다")
    void plansIncludeContractDiscountedFee() {
        insertPlan(mark + " 칠만오천", "FIVE_G", "GENERAL", 75000, 150000L, false, 5000);

        String result = planTools.searchPlans(mark, null, null, null, null, null, null, null, toolContext);

        assertThat(result).contains("월정액 75,000원", "선택약정 25% 할인 시 월 56,250원");
    }

    @Test
    @DisplayName("요금제 행에 설명의 규칙 문장을 붙이고, 컬럼과 겹치는 수량 구절은 뺀다")
    void plansIncludeDescriptionRules() {
        insertPlan(mark + " 청년", "FIVE_G", "YOUTH", 59000, 24000L, false, 1000,
                "월 데이터 24GB, 소진 후 최대 1Mbps로 계속 이용. 가입 대상 만 19~34세.");

        String result = planTools.searchPlans(mark, null, null, null, null, null, null, null, toolContext);

        assertThat(result).contains("| 가입 대상 만 19~34세.").doesNotContain("월 데이터 24GB");
    }

    @Test
    @DisplayName("요금제 카드에는 데이터·음성/문자·테더링 세 줄과 설명의 고유 정보, 네트워크 뱃지와 선택약정 할인가를 담는다")
    void planCardHasSpecsAndHighlights() {
        insertPlan(mark + " 청년", "FIVE_G", "YOUTH", 59000, 24000L, false, 1000,
                "월 데이터 24GB, 소진 후 최대 1Mbps로 계속 이용. 국내 일반 음성 기본제공, 국내 문자 기본제공. "
                        + "테더링 월 24GB이며 유한 데이터 상품은 기본 제공량에서 함께 차감. 표시 월정액은 부가세 포함, 기간 한정 할인 없이 유지. "
                        + "테더링 한도 소진 후 테더링 차단. 가입 대상 만 19~34세. 만 35세가 되는 달 말일까지 이용 가능. 이후 일반형 변경 상담 안내.");
        jdbcTemplate.update("UPDATE plans SET tethering_amount_mb = 24000 WHERE name = ?", mark + " 청년");

        planTools.searchPlans(mark, null, null, null, null, null, null, null, toolContext);

        // 테더링·부가세 문장은 판매 중인 요금제 대부분에 있는 공통 문장이라 고유 정보에서 빠지고, 테더링 규칙은 테더링 줄에 붙습니다.
        assertThat(recorder.cards()).singleElement().satisfies(card -> {
            assertThat(card.specs()).containsExactly("월 데이터 24GB, 소진 후 최대 1Mbps", "국내 일반 음성/문자 기본제공",
                    "테더링 월 24GB, 기본 데이터에서 함께 차감, 한도 소진 후 차단");
            assertThat(card.highlights()).containsExactly(
                    "가입 대상 만 19~34세", "만 35세가 되는 달 말일까지 이용 가능", "이후 일반형 변경 상담 안내");
            assertThat(card.network()).isEqualTo("5G");
            assertThat(card.feeNote()).isEqualTo("선택약정 25% 할인 시 월 44,250원");
        });
    }

    @Test
    @DisplayName("무제한 요금제 카드는 테더링 차감 조건 없이 쓰고, 무약정 요금제는 선택약정 할인가 문구가 없다")
    void unlimitedNoContractPlanCard() {
        insertPlan("Ubot 다이렉트 " + mark, "LTE", "GENERAL", 61000, null, true, null,
                "월 고속 데이터 무제한. 테더링 월 40GB이며 유한 데이터 상품은 기본 제공량에서 함께 차감. "
                        + "테더링 한도 소진 후 테더링 차단. 온라인 셀프개통 전용, 약정 없음.");
        jdbcTemplate.update("UPDATE plans SET tethering_amount_mb = 40000 WHERE name = ?", "Ubot 다이렉트 " + mark);

        planTools.searchPlans(mark, null, null, null, null, null, null, null, toolContext);

        assertThat(recorder.cards()).singleElement().satisfies(card -> {
            assertThat(card.specs()).containsExactly("월 고속 데이터 무제한", "국내 일반 음성/문자 기본제공",
                    "테더링 월 40GB, 한도 소진 후 차단");
            assertThat(card.highlights()).contains("온라인 셀프개통 전용, 약정 없음").doesNotContain("월 고속 데이터 무제한");
            assertThat(card.network()).isEqualTo("LTE");
            assertThat(card.feeNote()).isNull();
        });
    }

    @Test
    @DisplayName("결합 설명의 가입 조건은 컬럼에 없는 금액이 들어 있어도 행과 카드에 남긴다")
    void bundleConditionsWithAmountsAreKept() {
        insertBundle("Ubot " + mark + " 친구 2인", 6000,
                "친구 휴대폰 2회선. 각 회선 월정액 19,000원 이상 이용 시 결합 전체 월 6,000원 할인. 조건 미충족 다음 달 할인 종료.");

        String result = bundleTools.searchBundles(mark, null, null, null, toolContext);

        assertThat(result).contains("각 회선 월정액 19,000원 이상 이용 시 결합 전체 월 6,000원 할인.");
        assertThat(recorder.cards()).singleElement().satisfies(card -> assertThat(card.highlights())
                .containsExactly("친구 휴대폰 2회선", "각 회선 월정액 19,000원 이상 이용 시 결합 전체 월 6,000원 할인"));
    }

    @Test
    @DisplayName("돌려준 상품 모두에 있는 설명 문장은 행마다 쓰지 않고 공통 줄에 한 번만 쓴다")
    void sharedDescriptionSentencesAreWrittenOnce() {
        insertAddon("Ubot " + mark + " 컬러링", 990, "통화연결음 변경. 월 990원, 부가세 포함. 해지 전까지 월 단위 유지.");
        insertAddon("Ubot " + mark + " 표시", 0, "발신번호 표시. 월 0원, 부가세 포함. 해지 전까지 월 단위 유지.");

        String result = addonTools.searchAddons(mark, null, null, toolContext);

        assertThat(result).contains(String.format(ProductToolResults.COMMON_LINE, "부가서비스", "부가세 포함. 해지 전까지 월 단위 유지."))
                .contains("Ubot " + mark + " 표시 | 월 0원 | 발신번호 표시.\n")
                .contains("Ubot " + mark + " 컬러링 | 월 990원 | 통화연결음 변경.");
        assertThat(result.split("해지 전까지", -1)).hasSize(2);
        assertThat(recorder.describe()).contains("모두 공통");
        assertThat(recorder.cards()).hasSize(2);
    }

    @Test
    @DisplayName("네트워크·대상 조건은 작은 모델이 쓰는 표기도 받고, 쓸 수 없는 값이면 다시 호출하라고 돌려준다")
    void plansParseConditionsLeniently() {
        insertPlan(mark + " 청년", "FIVE_G", "YOUTH", 19000, 5000L, false, 400);
        insertPlan(mark + " 엘티이", "LTE", "YOUTH", 9000, 1000L, false, null);

        assertThat(planTools.searchPlans(mark, "5G", "youth", null, null, null, null, null, toolContext))
                .startsWith("조건에 맞는 요금제 1건 중 1건").contains(mark + " 청년", "대상 청년", "| 5G |");
        assertThat(planTools.searchPlans(mark, "6G", null, null, null, null, null, null, toolContext))
                .contains("'6G'은(는) networkType에 쓸 수 없는 값입니다");
        assertThat(planTools.searchPlans(mark, null, null, null, null, null, "CHEAP", null, toolContext))
                .contains("MONTHLY_FEE_ASC, DATA_DESC");
    }

    @Test
    @DisplayName("판매 중지·삭제된 상품은 돌려주지 않고, 없으면 추측하지 말라고 안내한다")
    void excludesInactiveAndDeletedProducts() {
        insertPlan(mark + " 중지", "LTE", "GENERAL", 5000, 1000L, false, null);
        jdbcTemplate.update("UPDATE plans SET status = 'INACTIVE' WHERE name = ?", mark + " 중지");
        insertPlan(mark + " 삭제", "LTE", "GENERAL", 5000, 1000L, false, null);
        jdbcTemplate.update("UPDATE plans SET deleted_at = CURRENT_TIMESTAMP WHERE name = ?", mark + " 삭제");

        assertThat(planTools.searchPlans(mark, null, null, null, null, null, null, null, toolContext))
                .isEqualTo(String.format(ProductToolResults.NOT_FOUND_MESSAGE, "요금제"));
        assertThat(recorder.cards()).isEmpty();
    }

    @Test
    @DisplayName("부가서비스는 월 요금 낮은 순으로 설명과 함께 돌려주고, 'Ubot' 없이 이름 일부로 찾는다")
    void addonsByMonthlyFee() {
        insertAddon("Ubot " + mark + " 비싼", 9900, "가상 영상 라이브러리");
        insertAddon("Ubot " + mark + " 무료", 0, "발신번호 표시");

        String result = addonTools.searchAddons(mark, null, null, toolContext);

        assertThat(result).startsWith("조건에 맞는 부가서비스 2건 중 2건(월 요금 낮은 순)")
                .contains("Ubot " + mark + " 무료 | 월 0원 | 발신번호 표시.");
        assertThat(result.indexOf("무료")).isLessThan(result.indexOf("비싼"));
        assertThat(recorder.cards()).first()
                .isEqualTo(new ProductCard(ProductType.ADDON, "Ubot " + mark + " 무료", 0, List.of(), List.of("발신번호 표시")));
    }

    @Test
    @DisplayName("결합은 요금 대신 할인액이 큰 순으로 돌려준다")
    void bundlesByDiscountDescending() {
        insertBundle("Ubot " + mark + " 2인", 6000);
        insertBundle("Ubot " + mark + " 4인", 20000);

        String result = bundleTools.searchBundles("Ubot " + mark, null, null, null, toolContext);

        assertThat(result).startsWith("조건에 맞는 결합 상품 2건 중 2건(할인액 큰 순)")
                .contains("결합 전체 월 20,000원 할인");
        assertThat(result.indexOf("4인")).isLessThan(result.indexOf("2인"));
    }

    @Test
    @DisplayName("부가서비스는 분류로 거르고, 이름 없이 분류만 있으면 분류 말을 목록 주어로 쓴다")
    void addonsByCategory() {
        insertAddon("Ubot " + mark + " 컬러링", 990, "통화연결음 변경", "CALL");
        insertAddon("Ubot " + mark + " 착신", 0, "착신전환", "CALL");
        insertAddon("Ubot " + mark + " 스팸", 0, "스팸 차단", "BLOCKING");

        String result = addonTools.searchAddons(mark, "call", null, toolContext);

        assertThat(result).startsWith("조건에 맞는 부가서비스 2건 중 2건").contains("컬러링", "착신").doesNotContain("스팸");

        var byCategory = new ProductDataRecorder();
        addonTools.searchAddons(null, "CALL", true, new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, byCategory)));
        assertThat(byCategory.listing()).hasValueSatisfying(found ->
                assertThat(((ProductListing) found).subject()).isEqualTo("통화 부가서비스"));
    }

    @Test
    @DisplayName("결합은 종류와 회선 수로 거르고, 회선 수 제한이 없는 결합은 어떤 회선 수에도 함께 돌려준다")
    void bundlesByTypeAndLineCount() {
        insertBundle("Ubot " + mark + " 가족 4인", 20000, "가족 4회선", "FAMILY", 4);
        insertBundle("Ubot " + mark + " 친구 4인", 9000, "친구 4회선", "ETC", 4);
        insertBundle("Ubot " + mark + " 가족 2인", 6000, "가족 2회선", "FAMILY", 2);
        insertBundle("Ubot " + mark + " 오래함께", 10000, "휴대폰 2회선 이상", "FAMILY", null);

        assertThat(bundleTools.searchBundles(mark, null, 4, null, toolContext))
                .startsWith("조건에 맞는 결합 상품 3건 중 3건").contains("가족 4인", "친구 4인", "오래함께")
                .doesNotContain("가족 2인");

        var byType = new ProductDataRecorder();
        String family = bundleTools.searchBundles(mark, "family", 4, true,
                new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, byType)));
        assertThat(family).startsWith("조건에 맞는 결합 상품 2건 중 2건").contains("가족 4인", "오래함께");
        assertThat(byType.listing()).hasValueSatisfying(found ->
                assertThat(((ProductListing) found).subject()).isEqualTo("조건에 맞는 " + mark + " 결합 상품"));
    }

    @Test
    @DisplayName("부가서비스 분류나 결합 종류에 쓸 수 없는 값이면 쓸 수 있는 값과 함께 다시 호출하라고 돌려준다")
    void invalidCategoryAndBundleTypeAskToRetry() {
        assertThat(addonTools.searchAddons(mark, "MUSIC", null, toolContext))
                .contains("'MUSIC'은(는) category에 쓸 수 없는 값입니다").contains("DATA_CHARGE, VOICE_CHARGE");
        assertThat(bundleTools.searchBundles(mark, "COUPLE", null, null, toolContext))
                .contains("'COUPLE'은(는) bundleType에 쓸 수 없는 값입니다").contains("FAMILY, HOME, DUO, ETC");
        assertThat(recorder.cards()).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("홈 결합은 필요한 인터넷 상품 이름을 행과 카드에 쓴다(조회 트랜잭션이 끝난 뒤에도 읽힌다)")
    void homeBundleShowsInternetProduct() {
        // 테스트 트랜잭션 안에서는 지연 로딩 오류가 가려지므로 트랜잭션 밖에서 넣고 지웁니다.
        String homeCode = UUID.randomUUID().toString();
        String bundleCode = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO home_products (code, name, product_type, speed_mbps, monthly_fee)
                VALUES (?, ?, 'INTERNET', 100, 22000)
                """, homeCode, "Ubot " + mark + " 인터넷 100M");
        jdbcTemplate.update("""
                INSERT INTO bundle_products (code, name, discount_amount, description, bundle_type, line_count, home_product_id)
                SELECT ?, ?, 3300, '휴대폰 1회선과 인터넷 이용 시 할인', 'HOME', 1, home_product_id FROM home_products WHERE code = ?
                """, bundleCode, "Ubot " + mark + " 홈 결합", homeCode);
        try {
            String result = bundleTools.searchBundles(mark, "HOME", null, null, toolContext);

            assertThat(result).contains("Ubot " + mark + " 홈 결합 | 결합 전체 월 3,300원 할인 | "
                    + BundleTools.HOME_PRODUCT_PREFIX + "Ubot " + mark + " 인터넷 100M");
            assertThat(recorder.cards()).singleElement().satisfies(card -> assertThat(card.specs())
                    .containsExactly(BundleTools.HOME_PRODUCT_PREFIX + "Ubot " + mark + " 인터넷 100M"));
        } finally {
            jdbcTemplate.update("DELETE FROM bundle_products WHERE code = ?", bundleCode);
            jdbcTemplate.update("DELETE FROM home_products WHERE code = ?", homeCode);
        }
    }

    @Test
    @DisplayName("로밍은 나라로 거르고 일 요금 낮은 순으로 돌려준다")
    void roamingByCountry() {
        insertRoaming("Ubot 로밍 " + mark + " 맥스", 15000, 5000L, mark + "국");
        insertRoaming("Ubot 로밍 " + mark + " 베이직", 11000, 1000L, mark + "국");
        insertRoaming("Ubot 로밍 " + mark + " 다른나라", 11000, 1000L, mark + "도");

        String result = roamingTools.searchRoaming(mark + "국", null, null, toolContext);

        assertThat(result).startsWith("조건에 맞는 로밍 상품 2건 중 2건(일 요금 낮은 순)")
                .contains("일 11,000원 | 데이터 1GB").doesNotContain("다른나라");
        assertThat(result.indexOf("베이직")).isLessThan(result.indexOf("맥스"));
    }

    @Test
    @DisplayName("로밍 결과에는 판매 국가 목록을 함께 넣고, 나라 이름에 도시가 붙어도 그 나라로 찾는다")
    void roamingListsCountriesAndFindsCountryInsideInput() {
        insertRoaming("Ubot 로밍 " + mark + " 베이직", 11000, 1000L, mark + "국");

        String result = roamingTools.searchRoaming(mark + "국 수도", null, null, toolContext);

        assertThat(result).startsWith("조건에 맞는 로밍 상품 1건 중 1건").contains("로밍 상품을 판매하는 나라(")
                .contains(mark + "국");
    }

    @Test
    @DisplayName("판매 국가가 아닌 지역이면 상품을 지어내지 말고 판매 국가 목록으로 다시 호출하게 한다")
    void roamingRegionWithoutCountryReturnsCountryList() {
        insertRoaming("Ubot 로밍 " + mark + " 베이직", 11000, 1000L, mark + "국");

        String result = roamingTools.searchRoaming("유럽", null, null, toolContext);

        assertThat(result).startsWith("'유럽'에 해당하는 나라의 로밍 상품이 없습니다.").contains(mark + "국")
                .contains("country에 그 나라 이름을 넣어 다시 호출");
        assertThat(recorder.cards()).isEmpty();
    }

    @Test
    @DisplayName("설명에 '약정 없음'이 있는 무약정 요금제는 선택약정 할인가 대신 미적용이라고 쓴다")
    void noContractPlansHaveNoContractDiscount() {
        insertPlan("Ubot 다이렉트 " + mark, "FIVE_G", "GENERAL", 49000, 120000L, false, 5000,
                "온라인 셀프개통 전용, 약정 없음.");
        insertPlan("Ubot 실속 " + mark, "LTE", "GENERAL", 3300, 500L, false, null, "USIM·eSIM 가입 가능. 약정 없음.");
        insertPlan("Ubot 밸런스 " + mark, "FIVE_G", "GENERAL", 59000, 24000L, false, 1000, "온라인·매장 가입 가능.");

        String result = planTools.searchPlans(mark, null, null, null, null, null, null, null, toolContext);

        assertThat(result.lines().filter(line -> line.contains("다이렉트") || line.contains("실속")))
                .hasSize(2).allSatisfy(line -> assertThat(line).contains(PlanTools.NO_CONTRACT_DISCOUNT)
                        .doesNotContain("% 할인 시"));
        assertThat(result.lines().filter(line -> line.contains("밸런스")))
                .singleElement().satisfies(line -> assertThat(line).contains("선택약정 25% 할인 시 월 44,250원"));
    }

    @Test
    @DisplayName("목록 요청이면 이름 조건을 주어로 한 목록을 기록하고, 목록 요청이 아니거나 한 건이면 기록하지 않는다")
    void listRequestRecordsListing() {
        insertPlan("Ubot " + mark + " 89", "FIVE_G", "GENERAL", 89000, null, true, null);
        insertPlan("Ubot " + mark + " 99", "FIVE_G", "GENERAL", 99000, null, true, null);

        planTools.searchPlans(mark + " 요금제", null, null, null, null, null, null, true, toolContext);

        assertThat(recorder.listing()).hasValueSatisfying(found -> {
            assertThat(found).isInstanceOf(ProductListing.class);
            ProductListing listing = (ProductListing) found;
            assertThat(listing.subject()).isEqualTo(mark + " 요금제");
            assertThat(listing.total()).isEqualTo(2);
            assertThat(listing.answer()).startsWith(mark + " 요금제는 다음 2가지가 있습니다.\n- Ubot " + mark + " 89 (월 89,000원)");
        });

        var notRequested = new ProductDataRecorder();
        planTools.searchPlans(mark, null, null, null, null, null, null, null,
                new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, notRequested)));
        assertThat(notRequested.listing()).isEmpty();

        var single = new ProductDataRecorder();
        planTools.searchPlans(mark + " 99", null, null, null, null, null, null, true,
                new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, single)));
        assertThat(single.listing()).isEmpty();
    }

    @Test
    @DisplayName("이름 조건은 종류 이름을 붙이거나 띄어쓰기를 바꿔 넣어도 찾는다")
    void namesMatchWithGenericWordsAndSpacing() {
        insertPlan("Ubot 밸런스 " + mark + " 59", "FIVE_G", "GENERAL", 59000, 24000L, false, 1000);
        insertAddon("Ubot " + mark + " 컬러링", 990, "통화연결음 변경");
        insertBundle("Ubot " + mark + " 가족 4인 결합", 20000);
        insertRoaming("Ubot 로밍 " + mark + " 베이직", 11000, 1000L, mark + "국");

        assertThat(planTools.searchPlans("밸런스" + mark + "59 요금제", null, null, null, null, null, null, null, toolContext))
                .startsWith("조건에 맞는 요금제 1건 중 1건");
        assertThat(addonTools.searchAddons(mark + "컬러링 서비스", null, null, toolContext)).startsWith("조건에 맞는 부가서비스 1건 중 1건");
        assertThat(bundleTools.searchBundles(mark + " 가족 결합", null, null, null, toolContext)).startsWith("조건에 맞는 결합 상품 1건 중 1건");
        assertThat(roamingTools.searchRoaming(null, mark + " 베이직 로밍", null, toolContext)).startsWith("조건에 맞는 로밍 상품 1건 중 1건");
    }

    @Test
    @DisplayName("도구가 돌려준 행을 출력 검사 근거로 그대로 남긴다")
    void recordsReturnedLines() {
        insertAddon("Ubot " + mark + " 컬러링", 990, "통화연결음 변경");

        String result = addonTools.searchAddons(mark, null, null, toolContext);

        assertThat(recorder.describe()).isNotBlank();
        assertThat(result).contains(recorder.describe());
    }

    private void insertPlan(String name, String network, String target, int fee, Long dataMb, boolean unlimited,
            Integer exhaustedKbps) {
        insertPlan(name, network, target, fee, dataMb, unlimited, exhaustedKbps, null);
    }

    private void insertPlan(String name, String network, String target, int fee, Long dataMb, boolean unlimited,
            Integer exhaustedKbps, String description) {
        jdbcTemplate.update("""
                INSERT INTO plans (plan_code, name, network_type, target_group, monthly_fee, data_amount_mb,
                    data_unlimited, exhausted_speed_kbps, voice_unlimited, sms_unlimited, tethering_amount_mb, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, 0, ?)
                """, UUID.randomUUID().toString(), name, network, target, fee, dataMb, unlimited, exhaustedKbps,
                description);
    }

    private void insertAddon(String name, int fee, String description) {
        insertAddon(name, fee, description, "ETC");
    }

    private void insertAddon(String name, int fee, String description, String category) {
        jdbcTemplate.update("INSERT INTO addon_services (code, name, monthly_fee, description, category) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), name, fee, description, category);
    }

    private void insertBundle(String name, int discount) {
        insertBundle(name, discount, "대표 회선에서 할인");
    }

    private void insertBundle(String name, int discount, String description) {
        insertBundle(name, discount, description, "ETC", null);
    }

    private void insertBundle(String name, int discount, String description, String bundleType, Integer lineCount) {
        jdbcTemplate.update("""
                INSERT INTO bundle_products (code, name, discount_amount, description, bundle_type, line_count)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), name, discount, description, bundleType, lineCount);
    }

    private void insertRoaming(String name, int dailyFee, long dataMb, String country) {
        jdbcTemplate.update("""
                INSERT INTO roaming_products (code, name, daily_fee, data_amount_mb, country, description)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), name, dailyFee, dataMb, country, "24시간 구간 과금");
    }
}
