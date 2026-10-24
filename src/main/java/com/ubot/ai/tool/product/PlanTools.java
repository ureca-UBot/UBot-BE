package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import com.ubot.ai.tool.product.ProductCatalogQuery.PlanCondition;
import com.ubot.ai.tool.product.ProductToolResults.InvalidValueException;
import com.ubot.plan.entity.Plan;
import com.ubot.plan.enums.NetworkType;
import com.ubot.plan.enums.PlanTargetGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    static final String NO_CONTRACT_DISCOUNT = "선택약정 할인 미적용(무약정 요금제)";

    private final ProductCatalogQuery productCatalogQuery;
    private final ProductNameIndex productNameIndex;
    private final int contractDiscountPercent;

    public PlanTools(ProductCatalogQuery productCatalogQuery, ProductNameIndex productNameIndex,
            @Value("${product.contract-discount-percent:25}") int contractDiscountPercent) {
        this.productCatalogQuery = productCatalogQuery;
        this.productNameIndex = productNameIndex;
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
            target = ProductToolResults.parseEnum(PlanTargetGroup.class, targetGroup, "targetGroup");
            sort = ProductToolResults.parseEnum(PlanSort.class, sortBy, "sortBy");
        } catch (InvalidValueException exception) {
            log.info("요금제 조회 도구 조건 오류: {}", exception.getMessage());
            return exception.getMessage();
        }
        ProductDataRecorder recorder = ProductToolResults.recorder(toolContext);
        try {
            // 모델이 "프리미엄 요금제 종류"의 "프리미엄"을 분류로 보고 이름을 비우면 전체 요금제가 나오므로, 질문의 시리즈 이름으로 채웁니다.
            if (!StringUtils.hasText(name)) {
                Optional<String> series = productNameIndex.planSeriesMentionedIn(recorder.question());
                if (series.isPresent()) {
                    log.info("요금제 조회 도구: 이름 조건이 비었지만 질문에 '{} 요금제'가 있어 이름 조건으로 넣었습니다", series.get());
                    name = series.get();
                }
            }
            ProductCatalogQuery.Found<Plan> found = productCatalogQuery.searchPlans(
                    new PlanCondition(name, network, target, maxMonthlyFee, minDataGb, dataUnlimited, sort));
            String order = sort == PlanSort.DATA_DESC ? "데이터 많은 순" : "월정액 낮은 순";
            log.info("요금제 조회 도구: name={}, networkType={}, targetGroup={}, maxMonthlyFee={}, minDataGb={}, "
                    + "dataUnlimited={}, sortBy={}, listRequested={}, 결과={}건 중 {}건", name, networkType, targetGroup,
                    maxMonthlyFee, minDataGb, dataUnlimited, sortBy, listRequested, found.total(), found.items().size());
            boolean unfiltered = !StringUtils.hasText(name) && network == null && target == null
                    && maxMonthlyFee == null && minDataGb == null && dataUnlimited == null;
            // 조건 없이 요금제 종류를 물었는데 결과가 다 들어가지 않으면, 월정액 낮은 순 5개는 종류에 대한 답이 아니므로
            // 시리즈별 개요로 답합니다. 질문에 시리즈 이름이 둘 이상 있어 하나로 정하지 못한 경우도 개요에 그 시리즈들이 보입니다.
            if (Boolean.TRUE.equals(listRequested) && unfiltered && found.total() > ProductCatalogQuery.LIMIT) {
                Optional<PlanSeriesOverview> overview = productNameIndex.planOverview();
                if (overview.isPresent()) {
                    log.info("요금제 조회 도구: 조건 없는 목록 요청이라 시리즈 개요로 답합니다: 시리즈수={}", overview.get().groups().size());
                    // 개요의 개수·월정액 범위는 조회한 5개 행에 없으므로 출력 검사 근거로 남깁니다.
                    recorder.note(overview.get().answer());
                    return ProductToolResults.format(PRODUCT_LABEL, order, found, this::line, ProductDescriptions::of,
                            this::card, recorder, overview.get());
                }
            }
            // 아무 조건 없이 전체를 조회했는데 질문에 시리즈 이름이 있으면(둘 이상이라 하나로 정하지 못한 경우) 걸렀어야 할 조회라
            // 목록 답변으로 확정하지 않고 모델 답변에 맡깁니다.
            boolean unfilteredSeriesQuestion = unfiltered && productNameIndex.mentionsPlanSeries(recorder.question());
            if (Boolean.TRUE.equals(listRequested) && unfilteredSeriesQuestion) {
                log.info("요금제 조회 도구: 질문에 시리즈 이름이 있는데 조건 없이 조회해 목록 답변을 만들지 않습니다");
            }
            String listSubject = unfilteredSeriesQuestion ? null : ProductToolResults.listSubject(listRequested, PRODUCT_LABEL,
                    maxMonthlyFee != null || minDataGb != null || Boolean.FALSE.equals(dataUnlimited),
                    network == null ? null : networkLabel(network), target == null ? null : targetLabel(target),
                    Boolean.TRUE.equals(dataUnlimited) ? "데이터 무제한" : null,
                    ProductCatalogQuery.displayName(name, ProductCatalogQuery.PLAN_WORDS));
            return ProductToolResults.format(PRODUCT_LABEL, order, found, this::line, ProductDescriptions::of,
                    this::card, recorder, listSubject);
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

    /** 카드에는 행의 핵심 수치(네트워크·데이터·음성·문자·테더링·선택약정)와 설명의 고유 문장을 목록으로 담습니다. */
    private ProductCard card(Plan plan) {
        List<String> specs = new ArrayList<>();
        if (plan.getNetworkType() != null) {
            specs.add(networkLabel(plan.getNetworkType()));
        }
        specs.add("데이터 " + data(plan));
        String voice = allowance(plan.isVoiceUnlimited(), plan.getVoiceMinutes(), "분");
        String sms = allowance(plan.isSmsUnlimited(), plan.getSmsCount(), "건");
        specs.add(voice.equals(sms) ? "음성·문자 " + voice : "음성 " + voice + ", 문자 " + sms);
        specs.add("테더링 " + tethering(plan.getTetheringAmountMb()));
        specs.add(contractFee(plan));
        return new ProductCard(ProductType.PLAN, plan.getName(), plan.getMonthlyFee(), specs,
                ProductDescriptions.highlights(ProductDescriptions.of(plan), productNameIndex.commonSentences(ProductType.PLAN)));
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
        throw new InvalidValueException(String.format(ProductToolResults.INVALID_VALUE_MESSAGE, value, "networkType", "5G, LTE"));
    }

}
