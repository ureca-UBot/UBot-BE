package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import com.ubot.ai.tool.product.ProductCatalogQuery.PlanCondition;
import com.ubot.plan.entity.Plan;
import com.ubot.plan.enums.NetworkType;
import com.ubot.plan.enums.PlanTargetGroup;
import java.util.Arrays;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * LLM이 요금제 정보가 필요할 때 호출하는 요금제 조회 도구입니다.
 * 선택약정 할인가는 LLM이 계산하지 않도록 코드가 계산해 할인율과 함께 행에 넣습니다. 할인율은 설정값이 원본입니다.
 * 설명에 "약정 없음"이 있는 무약정 요금제는 선택약정 할인을 받을 수 없어 할인가 대신 미적용이라고 씁니다.
 * 열거형 조건은 작은 모델이 "5G"처럼 값 이름과 다르게 넣기도 해서 문자열로 받아 너그럽게 해석합니다.
 */
@Slf4j
@Component
public class PlanTools {

    static final String PRODUCT_LABEL = "요금제";
    static final String INVALID_VALUE_MESSAGE = "'%s'은(는) %s에 쓸 수 없는 값입니다. %s 중 하나를 넣거나 비워서 다시 호출하세요.";
    static final String NO_CONTRACT_DISCOUNT = "선택약정 할인 미적용(무약정 요금제)";

    private final ProductCatalogQuery productCatalogQuery;
    private final int contractDiscountPercent;

    public PlanTools(ProductCatalogQuery productCatalogQuery,
            @Value("${product.contract-discount-percent:25}") int contractDiscountPercent) {
        this.productCatalogQuery = productCatalogQuery;
        this.contractDiscountPercent = contractDiscountPercent;
    }

    @Tool(description = "Ubot 요금제의 월정액, 선택약정 할인가, 데이터·음성·문자·테더링 제공량, 가입 조건을 조회한다. "
            + "사용자가 말한 조건만 넣고 나머지는 비운다. 결과는 최대 5개이며 기본은 월정액이 낮은 순이다. "
            + "사용자가 데이터가 많은 순서를 원하면 sortBy에 DATA_DESC를 넣는다.")
    public String searchPlans(
            @ToolParam(description = "요금제 이름 일부. 예: 밸런스, 다이렉트 49. 사용자가 이름을 말하지 않았으면 비운다.", required = false)
            String name,
            @ToolParam(description = "네트워크. 5G 또는 LTE.", required = false) String networkType,
            @ToolParam(description = "가입 대상. GENERAL(일반), YOUTH(청년), SENIOR(시니어), KIDS(키즈), WATCH(워치), TABLET(태블릿).",
                    required = false) String targetGroup,
            @ToolParam(description = "월정액 상한(원). 예: 10만원 이하면 100000.", required = false) Integer maxMonthlyFee,
            @ToolParam(description = "최소 데이터 제공량(GB). 무제한 요금제도 포함된다.", required = false) Integer minDataGb,
            @ToolParam(description = "데이터 무제한 요금제만 찾으면 true.", required = false) Boolean dataUnlimited,
            @ToolParam(description = "정렬. MONTHLY_FEE_ASC(월정액 낮은 순, 기본) 또는 DATA_DESC(데이터 많은 순).", required = false)
            String sortBy,
            @ToolParam(description = ProductToolResults.LIST_REQUESTED_DESCRIPTION, required = false) Boolean listRequested,
            ToolContext toolContext) {
        NetworkType network;
        PlanTargetGroup target;
        PlanSort sort;
        try {
            network = parseNetwork(networkType);
            target = parseEnum(PlanTargetGroup.class, targetGroup, "targetGroup");
            sort = parseEnum(PlanSort.class, sortBy, "sortBy");
        } catch (InvalidValueException exception) {
            log.info("요금제 조회 도구 조건 오류: {}", exception.getMessage());
            return exception.getMessage();
        }
        ProductDataRecorder recorder = ProductToolResults.recorder(toolContext);
        try {
            ProductCatalogQuery.Found<Plan> found = productCatalogQuery.searchPlans(
                    new PlanCondition(name, network, target, maxMonthlyFee, minDataGb, dataUnlimited, sort));
            String order = sort == PlanSort.DATA_DESC ? "데이터 많은 순" : "월정액 낮은 순";
            log.info("요금제 조회 도구: name={}, networkType={}, targetGroup={}, maxMonthlyFee={}, minDataGb={}, "
                    + "dataUnlimited={}, sortBy={}, listRequested={}, 결과={}건 중 {}건", name, networkType, targetGroup,
                    maxMonthlyFee, minDataGb, dataUnlimited, sortBy, listRequested, found.total(), found.items().size());
            String listSubject = ProductToolResults.listSubject(listRequested, PRODUCT_LABEL,
                    maxMonthlyFee != null || minDataGb != null || Boolean.FALSE.equals(dataUnlimited),
                    network == null ? null : networkLabel(network), target == null ? null : targetLabel(target),
                    Boolean.TRUE.equals(dataUnlimited) ? "데이터 무제한" : null,
                    ProductCatalogQuery.displayName(name, ProductCatalogQuery.PLAN_WORDS));
            return ProductToolResults.format(PRODUCT_LABEL, order, found, this::line, Plan::getDescription,
                    PlanTools::card, recorder, listSubject);
        } catch (RuntimeException exception) {
            // 도구 안의 DB 오류가 답변 전체 실패로 번지지 않게 안내 문구로 돌려줍니다.
            log.warn("요금제 조회 도구 실패: name={}, networkType={}, targetGroup={}", name, networkType, targetGroup, exception);
            return ProductToolResults.LOOKUP_FAILED_MESSAGE;
        }
    }

    /** 선택약정 할인을 받았을 때의 월 요금입니다. 1원 미만은 버립니다. */
    Integer contractDiscountedFee(Integer monthlyFee) {
        return monthlyFee == null ? null : monthlyFee * (100 - contractDiscountPercent) / 100;
    }

    private String contractFee(Plan plan) {
        return ProductDescriptions.isNoContract(plan.getDescription()) ? NO_CONTRACT_DISCOUNT
                : "선택약정 " + contractDiscountPercent + "% 할인 시 월 "
                        + ToolFormats.won(contractDiscountedFee(plan.getMonthlyFee()));
    }

    private String line(Plan plan) {
        return ToolFormats.valueOrUnknown(plan.getName())
                + " | " + networkLabel(plan.getNetworkType()) + " | 대상 " + targetLabel(plan.getTargetGroup())
                + " | 월정액 " + ToolFormats.won(plan.getMonthlyFee())
                + " | " + contractFee(plan)
                + " | 데이터 " + data(plan)
                + " | 음성 " + allowance(plan.isVoiceUnlimited(), plan.getVoiceMinutes(), "분")
                + " | 문자 " + allowance(plan.isSmsUnlimited(), plan.getSmsCount(), "건")
                + " | 테더링 " + tethering(plan.getTetheringAmountMb());
    }

    /** 소진 후 속도가 없는 요금제는 데이터를 다 쓰면 차단됩니다(실속·태블릿·워치 등). */
    private static String data(Plan plan) {
        if (plan.isDataUnlimited()) {
            return "무제한";
        }
        Long mb = plan.getDataAmountMb();
        if (mb != null && mb == 0) {
            return "제공 안 함";
        }
        return ProductToolResults.data(mb) + (plan.getExhaustedSpeedKbps() == null ? "(소진 후 차단)"
                : "(소진 후 최대 " + ToolFormats.speed(plan.getExhaustedSpeedKbps()) + ")");
    }

    private static ProductCard card(Plan plan) {
        return new ProductCard(ProductType.PLAN, plan.getName(), plan.getMonthlyFee(), plan.getDescription());
    }

    private static String allowance(boolean unlimited, Integer amount, String unit) {
        if (unlimited) {
            return "기본 제공";
        }
        if (amount == null || amount == 0) {
            return "제공 안 함";
        }
        return amount + unit;
    }

    private static String tethering(Long mb) {
        return mb == null || mb == 0 ? "제공 안 함" : ProductToolResults.data(mb);
    }

    private static String networkLabel(NetworkType type) {
        if (type == null) return "정보 없음";
        return switch (type) {
            case FIVE_G -> "5G";
            case LTE -> "LTE";
        };
    }

    private static String targetLabel(PlanTargetGroup group) {
        if (group == null) return "정보 없음";
        return switch (group) {
            case GENERAL -> "일반";
            case YOUTH -> "청년";
            case SENIOR -> "시니어";
            case KIDS -> "키즈";
            case WATCH -> "워치";
            case TABLET -> "태블릿";
        };
    }

    private static NetworkType parseNetwork(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace(" ", "");
        if (normalized.equals("5G") || normalized.equals("FIVE_G")) {
            return NetworkType.FIVE_G;
        }
        if (normalized.equals("LTE") || normalized.equals("4G")) {
            return NetworkType.LTE;
        }
        throw new InvalidValueException(String.format(INVALID_VALUE_MESSAGE, value, "networkType", "5G, LTE"));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String parameter) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            String allowed = String.join(", ", Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
            throw new InvalidValueException(String.format(INVALID_VALUE_MESSAGE, value, parameter, allowed));
        }
    }

    /** LLM이 넣은 조건 값을 해석할 수 없을 때 재요청 문구를 돌려주려고 씁니다. */
    private static final class InvalidValueException extends RuntimeException {
        InvalidValueException(String message) {
            super(message);
        }
    }
}
