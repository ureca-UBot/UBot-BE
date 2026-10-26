package com.ubot.ai.tool.product;

import com.ubot.addon.enums.AddonCategory;
import com.ubot.bundle.enums.BundleType;
import com.ubot.plan.enums.NetworkType;
import com.ubot.plan.enums.PlanTargetGroup;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 상품 목록 결과가 많거나 없을 때 화면이 보여 줄 조건 선택지입니다. 사용자가 고른 값은 버튼 조건 조회 API로 다시 조회합니다.
 * 선택지는 고정 목록(열거형 값, 정해진 구간)이고 건수는 세지 않습니다. 고른 조합이 0건이면 그 결과에 선택지가 다시 붙습니다.
 * 도구가 적용한 값이 정해진 구간에 없으면(예: 월정액 6만원 이하) 그 값을 선택지에 더해 선택된 상태로 둡니다.
 * 이름 조건은 선택지가 없어 keyword로 따로 두고, 화면은 "검색어" 칩으로 보여 줍니다.
 *
 * @param total 적용한 조건에 맞는 전체 상품 수
 */
public record ProductRefine(ProductType type, String keyword, long total, List<Group> groups) {

    // 묶음 키입니다. 버튼 조건 조회 요청의 conditions 키와 같습니다.
    public static final String NETWORK = "networkType";
    public static final String TARGET = "targetGroup";
    public static final String MAX_FEE = "maxMonthlyFee";
    /** 요금제 데이터 묶음입니다. 값은 최소 GB 수이거나 DATA_UNLIMITED입니다. */
    public static final String DATA = "data";
    public static final String SORT = "sortBy";
    public static final String CATEGORY = "category";
    public static final String BUNDLE_TYPE = "bundleType";
    public static final String LINE_COUNT = "lineCount";
    public static final String COUNTRY = "country";
    public static final String DATA_UNLIMITED = "UNLIMITED";

    static final List<Integer> FEE_CAPS = List.of(30000, 50000, 70000, 100000);
    static final List<Integer> DATA_FLOORS_GB = List.of(10, 50, 100);
    static final List<Integer> LINE_COUNTS = List.of(2, 3, 4, 5);

    /** 종류별로 버튼 조건 조회가 받는 conditions 키입니다. */
    public static final Map<ProductType, List<String>> KEYS = Map.of(
            ProductType.PLAN, List.of(NETWORK, TARGET, MAX_FEE, DATA, SORT),
            ProductType.ADDON, List.of(CATEGORY),
            ProductType.BUNDLE, List.of(BUNDLE_TYPE, LINE_COUNT),
            ProductType.ROAMING, List.of(COUNTRY));

    public ProductRefine {
        groups = List.copyOf(groups);
    }

    /**
     * 조건 한 줄입니다. selected는 지금 적용된 값이고 null이면 "상관없음"입니다.
     * anyAllowed가 false면 "상관없음"이 없습니다. 부가서비스 분류는 묶음이 하나뿐이라 상관없음이면 고를 의미가 없고,
     * 로밍 나라는 상관없음이면 모든 나라가 다시 나오며, 정렬은 기본값이 있기 때문입니다.
     */
    public record Group(String key, String label, String selected, boolean anyAllowed, List<Option> options) {

        public Group {
            options = List.copyOf(options);
        }
    }

    public record Option(String value, String label) {
    }

    /**
     * 단계별 선택에서 한 묶음의 선택지마다 결과가 있는지입니다. 건수는 담지 않습니다.
     *
     * @param anyAvailable 이 묶음을 "상관없음"으로 두었을 때 결과가 있는지
     */
    public record Availability(String key, boolean anyAvailable, List<OptionAvailability> options) {

        public Availability {
            options = List.copyOf(options);
        }
    }

    /** available은 앞 단계까지 고른 값에 이 선택지를 더한 조건에 맞는 상품이 하나라도 있는지입니다. */
    public record OptionAvailability(String value, boolean available) {
    }

    static ProductRefine of(ProductSearch search, long total) {
        Map<String, String> applied = search.conditions();
        List<Group> groups = switch (search.type()) {
            case PLAN -> List.of(
                    group(NETWORK, "네트워크", true, applied.get(NETWORK),
                            enumOptions(NetworkType.values(), PlanTools::networkLabel), Function.identity()),
                    group(TARGET, "대상", true, applied.get(TARGET),
                            enumOptions(PlanTargetGroup.values(), PlanTools::targetLabel), Function.identity()),
                    group(MAX_FEE, "월정액", true, applied.get(MAX_FEE),
                            numberOptions(FEE_CAPS, ProductRefine::feeLabel), ProductRefine::feeLabel),
                    group(DATA, "데이터", true, applied.get(DATA), dataOptions(), ProductRefine::dataLabel),
                    group(SORT, "정렬", false, applied.getOrDefault(SORT, PlanSort.MONTHLY_FEE_ASC.name()),
                            List.of(new Option(PlanSort.MONTHLY_FEE_ASC.name(), "월정액 낮은 순"),
                                    new Option(PlanSort.DATA_DESC.name(), "데이터 많은 순")), Function.identity()));
            case ADDON -> List.of(group(CATEGORY, "분류", false, applied.get(CATEGORY),
                    enumOptions(AddonCategory.values(), AddonTools::categoryLabel), Function.identity()));
            case BUNDLE -> List.of(
                    group(BUNDLE_TYPE, "종류", true, applied.get(BUNDLE_TYPE),
                            enumOptions(BundleType.values(), BundleTools::typeLabel), Function.identity()),
                    group(LINE_COUNT, "회선 수", true, applied.get(LINE_COUNT),
                            numberOptions(LINE_COUNTS, ProductRefine::lineLabel), ProductRefine::lineLabel));
            case ROAMING -> List.of(group(COUNTRY, "나라", false, applied.get(COUNTRY),
                    search.countries().stream().map(country -> new Option(country, country)).toList(),
                    Function.identity()));
        };
        return new ProductRefine(search.type(), search.keyword(), total, groups);
    }

    /** 적용된 값이 선택지에 없으면 그 값을 끝에 더합니다. 다음 요청에서 조건이 빠지지 않게 하려는 것입니다. */
    private static Group group(String key, String label, boolean anyAllowed, String selected, List<Option> options,
            Function<String, String> extraLabel) {
        if (selected == null || options.stream().anyMatch(option -> option.value().equals(selected))) {
            return new Group(key, label, selected, anyAllowed, options);
        }
        List<Option> extended = new ArrayList<>(options);
        extended.add(new Option(selected, extraLabel.apply(selected)));
        return new Group(key, label, selected, anyAllowed, extended);
    }

    private static <E extends Enum<E>> List<Option> enumOptions(E[] values, Function<E, String> label) {
        return Arrays.stream(values).map(value -> new Option(value.name(), label.apply(value))).toList();
    }

    private static List<Option> numberOptions(List<Integer> values, Function<String, String> label) {
        return values.stream().map(String::valueOf).map(value -> new Option(value, label.apply(value))).toList();
    }

    private static List<Option> dataOptions() {
        List<Option> options = new ArrayList<>(numberOptions(DATA_FLOORS_GB, ProductRefine::dataLabel));
        options.add(new Option(DATA_UNLIMITED, dataLabel(DATA_UNLIMITED)));
        return options;
    }

    /** 만원 단위면 "3만원 이하", 아니면 "65,000원 이하"입니다. */
    static String feeLabel(String value) {
        int fee = Integer.parseInt(value);
        return (fee % 10000 == 0 ? fee / 10000 + "만원" : String.format(Locale.ROOT, "%,d원", fee)) + " 이하";
    }

    static String dataLabel(String value) {
        return DATA_UNLIMITED.equals(value) ? "무제한만" : value + "GB 이상";
    }

    static String lineLabel(String value) {
        return value + "회선";
    }
}
