package com.ubot.ai.tool.product;

import com.ubot.addon.entity.AddonService;
import com.ubot.bundle.entity.BundleProduct;
import com.ubot.plan.entity.Plan;
import com.ubot.roaming.entity.RoamingProduct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 상품 설명(description)을 도구 결과 행과 화면 카드에 넣을 문장으로 다듬습니다. 상품별 가입 대상·포함 혜택·이용 조건 같은 규칙은
 * FAQ가 아니라 이 설명이 원본입니다.
 * 설명에는 "월 데이터 24GB", "월 3,300원"처럼 그 상품의 컬럼 값을 다시 쓴 구절이 섞여 있습니다. 그 값은 행에 이미 있으므로
 * 구절(쉼표로 나눈 단위)이 컬럼 값만 다시 쓴 것이면 뺍니다. 컬럼 값만 다시 쓴 구절은 수량이 모두 그 상품의 컬럼 값과 같고,
 * 수량을 가린 나머지가 행에 있는 항목 이름(종류별 *_LABELS)뿐인 구절입니다.
 * "테더링 월 24GB이며 유한 데이터 상품은 기본 제공량에서 함께 차감"처럼 수량이 컬럼과 같아도 조건이 붙은 구절은
 * 수량째 남깁니다. 수량만 떼면 무엇의 조건인지 알 수 없기 때문입니다. 항목 이름 목록에 없는 구절도 남깁니다.
 * "각 회선 월정액 19,000원 이상"(결합 조건), "매월 1GB 추가"(부가서비스 충전량)처럼 컬럼에 없는 수량도 규칙이라 남깁니다.
 */
final class ProductDescriptions {

    // 컬럼과 비교할 수량입니다. 데이터는 MB, 속도는 Kbps로 맞춥니다. "만 19~34세", "1회선", "24시간"은 컬럼과 비교하지 않습니다.
    private static final Pattern QUANTITY = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(TB|GB|MB|Gbps|Mbps|Kbps|kbps|원|분|건)");
    private static final Map<String, Unit> UNITS = Map.of(
            "TB", new Unit("MB", 1_000_000), "GB", new Unit("MB", 1_000), "MB", new Unit("MB", 1),
            "Gbps", new Unit("Kbps", 1_000_000), "Mbps", new Unit("Kbps", 1_000), "Kbps", new Unit("Kbps", 1),
            "kbps", new Unit("Kbps", 1), "원", new Unit("원", 1), "분", new Unit("분", 1), "건", new Unit("건", 1));
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=\\.)\\s+");
    private static final Pattern CLAUSE_BREAK = Pattern.compile(",\\s+");
    // 관리자는 무약정 요금제 설명에 이 구절을 넣습니다(예: "온라인 셀프개통 전용, 약정 없음.").
    private static final Pattern NO_CONTRACT = Pattern.compile("무약정|약정\\s*없음");
    private static final String QUANTITY_MARK = "{}";
    // 도구 결과 행이 이미 보여 주는 항목입니다. 수량 자리는 QUANTITY_MARK입니다(shape 참고).
    static final Set<String> PLAN_LABELS = Set.of(
            "월 데이터 {}", "소진 후 최대 {}로 계속 이용", "국내 일반 음성 {}", "국내 문자 {}", "테더링 월 {}");
    static final Set<String> ADDON_LABELS = Set.of("월 {}");
    static final Set<String> BUNDLE_LABELS = Set.of("결합 전체 월 {} 할인");
    static final Set<String> ROAMING_LABELS = Set.of("일 {}", "데이터 {}");
    /** 카드에 보여 줄 설명 문장 수입니다. */
    static final int HIGHLIGHT_LIMIT = 3;

    private ProductDescriptions() {
    }

    static List<String> of(Plan plan) {
        return sentences(plan.getDescription(), quantities(
                "MB", plan.getDataAmountMb(), "MB", plan.getTetheringAmountMb(), "Kbps", plan.getExhaustedSpeedKbps(),
                "분", plan.getVoiceMinutes(), "건", plan.getSmsCount(), "원", plan.getMonthlyFee()), PLAN_LABELS);
    }

    static List<String> of(AddonService addon) {
        return sentences(addon.getDescription(), quantities("원", addon.getMonthlyFee()), ADDON_LABELS);
    }

    static List<String> of(BundleProduct bundle) {
        return sentences(bundle.getDescription(), quantities("원", bundle.getDiscountAmount()), BUNDLE_LABELS);
    }

    static List<String> of(RoamingProduct roaming) {
        return sentences(roaming.getDescription(), quantities("원", roaming.getDailyFee(), "MB", roaming.getDataAmountMb()),
                ROAMING_LABELS);
    }

    /**
     * 컬럼 값만 다시 쓴 구절을 뺀 문장입니다. 각 문장은 마침표로 끝나고, 남는 문장이 없으면 빈 목록입니다.
     * columnQuantities는 quantities()로 만든 그 상품의 컬럼 값이고, columnLabels는 행이 보여 주는 항목 이름입니다.
     */
    static List<String> sentences(String description, Set<String> columnQuantities, Set<String> columnLabels) {
        List<String> sentences = new ArrayList<>();
        for (List<String> clauses : clausesBySentence(description)) {
            List<String> kept = clauses.stream()
                    .filter(clause -> !restatesColumns(clause, columnQuantities, columnLabels)).toList();
            if (!kept.isEmpty()) {
                sentences.add(String.join(", ", kept) + ".");
            }
        }
        return sentences;
    }

    /**
     * 카드에 목록으로 보여 줄 상품 고유 문장입니다. 같은 종류 상품 다수에 있는 문장(common, shape로 비교)을 빼고
     * 앞에서부터 HIGHLIGHT_LIMIT개를 마침표 없이 돌려줍니다.
     */
    static List<String> highlights(List<String> sentences, Set<String> common) {
        return sentences.stream().filter(sentence -> !common.contains(shape(sentence)))
                .map(sentence -> sentence.replaceAll("\\.$", "")).limit(HIGHLIGHT_LIMIT).toList();
    }

    /** 설명에 "약정 없음" 구절이 있는 요금제입니다. 이런 요금제는 선택약정 할인을 받을 수 없습니다. */
    static boolean isNoContract(String description) {
        return clausesBySentence(description).stream().flatMap(List::stream)
                .anyMatch(clause -> NO_CONTRACT.matcher(clause).matches());
    }

    /** "단위, 값" 쌍으로 받은 컬럼 값을 비교용 표기로 바꿉니다. 값이 없는 컬럼은 건너뜁니다. */
    static Set<String> quantities(Object... unitAndValues) {
        Set<String> keys = new HashSet<>();
        for (int index = 0; index + 1 < unitAndValues.length; index += 2) {
            if (unitAndValues[index + 1] instanceof Number value) {
                keys.add(key((String) unitAndValues[index], new BigDecimal(value.toString())));
            }
        }
        return keys;
    }

    /**
     * 수량을 QUANTITY_MARK로 가린 문장의 틀입니다. "테더링 월 24GB이며 …"와 "테더링 월 20GB이며 …"처럼
     * 수량만 다른 문장은 틀이 같아 같은 종류의 공통 문장으로 봅니다.
     */
    static String shape(String text) {
        return QUANTITY.matcher(text).replaceAll(QUANTITY_MARK).replaceAll("\\s+", " ").strip();
    }

    /** 구절에 수량이 있고, 그 수량이 모두 상품의 컬럼 값과 같으며, 수량을 가린 나머지가 행의 항목 이름뿐인지입니다. */
    private static boolean restatesColumns(String clause, Collection<String> columnQuantities,
            Set<String> columnLabels) {
        if (!columnLabels.contains(shape(clause))) {
            return false;
        }
        Matcher matcher = QUANTITY.matcher(clause);
        boolean found = false;
        while (matcher.find()) {
            Unit unit = UNITS.get(matcher.group(2));
            BigDecimal value = new BigDecimal(matcher.group(1).replace(",", "")).multiply(BigDecimal.valueOf(unit.factor()));
            if (!columnQuantities.contains(key(unit.base(), value))) {
                return false;
            }
            found = true;
        }
        return found;
    }

    private static String key(String unit, BigDecimal value) {
        return unit + ":" + value.stripTrailingZeros().toPlainString();
    }

    private static List<List<String>> clausesBySentence(String description) {
        if (!StringUtils.hasText(description)) {
            return List.of();
        }
        return Arrays.stream(SENTENCE_BREAK.split(description.strip()))
                .map(sentence -> sentence.strip().replaceAll("\\.$", ""))
                .filter(StringUtils::hasText)
                .map(sentence -> Arrays.stream(CLAUSE_BREAK.split(sentence)).map(String::strip)
                        .filter(StringUtils::hasText).toList())
                .toList();
    }

    private record Unit(String base, long factor) {
    }
}
