package com.ubot.ai.tool;

import com.ubot.addon.entity.AddonService;
import com.ubot.addon.repository.AddonServiceRepository;
import com.ubot.bundle.entity.BundleProduct;
import com.ubot.bundle.repository.BundleProductRepository;
import com.ubot.common.entity.AdminManagedEntity;
import com.ubot.common.enums.MasterStatus;
import com.ubot.common.repository.ProductSpecification;
import com.ubot.plan.entity.Plan;
import com.ubot.plan.enums.NetworkType;
import com.ubot.plan.enums.PlanTargetGroup;
import com.ubot.plan.repository.PlanRepository;
import com.ubot.roaming.entity.RoamingProduct;
import com.ubot.roaming.repository.RoamingProductRepository;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 상품 조회 도구가 쓰는 판매 중(ACTIVE·미삭제) 상품 조회입니다. 모든 조회는 최대 LIMIT개만 돌려주고 전체 건수를 함께 알려 줍니다.
 * 이름 조건은 공백을 무시한 부분 일치이고, 동률은 ID 순입니다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductCatalogQuery {

    public static final int LIMIT = 5;
    // DB는 데이터량을 1GB = 1000MB로 저장합니다.
    static final long MB_PER_GB = 1000L;
    private static final String BRAND = "ubot";
    // 이름 조건에서 뗄 종류별 일반 단어입니다. 긴 말부터 적어 "부가서비스"를 "서비스"보다 먼저 뗍니다.
    static final List<String> PLAN_WORDS = List.of("요금제", "상품");
    static final List<String> ADDON_WORDS = List.of("부가서비스", "서비스", "상품");
    static final List<String> BUNDLE_WORDS = List.of("결합상품", "결합", "상품");
    static final List<String> ROAMING_WORDS = List.of("로밍상품", "로밍", "상품");

    private final PlanRepository planRepository;
    private final AddonServiceRepository addonServiceRepository;
    private final BundleProductRepository bundleProductRepository;
    private final RoamingProductRepository roamingProductRepository;

    /** 요금제 조건입니다. null인 조건은 걸지 않습니다. */
    public record PlanCondition(String name, NetworkType networkType, PlanTargetGroup targetGroup,
            Integer maxMonthlyFee, Integer minDataGb, Boolean dataUnlimited, PlanSort sortBy) {
    }

    /** 조회한 상품과, 조건에 맞는 전체 건수입니다. */
    public record Found<T>(List<T> items, long total) {

        static <T> Found<T> of(Page<T> page) {
            return new Found<>(page.getContent(), page.getTotalElements());
        }
    }

    public Found<Plan> searchPlans(PlanCondition condition) {
        Specification<Plan> specification = active(condition.name(), PLAN_WORDS);
        if (condition.networkType() != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("networkType"), condition.networkType()));
        }
        if (condition.targetGroup() != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("targetGroup"), condition.targetGroup()));
        }
        if (condition.maxMonthlyFee() != null) {
            specification = specification.and((root, query, builder) ->
                    builder.le(root.get("monthlyFee"), condition.maxMonthlyFee()));
        }
        if (condition.minDataGb() != null) {
            // 무제한 요금제는 데이터량이 비어 있지만 어떤 최소량도 만족합니다.
            long minimumMb = condition.minDataGb() * MB_PER_GB;
            specification = specification.and((root, query, builder) -> builder.or(
                    builder.isTrue(root.get("dataUnlimited")),
                    builder.ge(root.get("dataAmountMb"), minimumMb)));
        }
        if (condition.dataUnlimited() != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("dataUnlimited"), condition.dataUnlimited()));
        }
        Sort sort = condition.sortBy() == PlanSort.DATA_DESC
                ? Sort.by(Order.desc("dataUnlimited"), Order.desc("dataAmountMb"), Order.asc("monthlyFee"),
                        Order.asc("planId"))
                : Sort.by(Order.asc("monthlyFee"), Order.asc("planId"));
        return Found.of(planRepository.findAll(specification, PageRequest.of(0, LIMIT, sort)));
    }

    public Found<AddonService> searchAddons(String name) {
        return Found.of(addonServiceRepository.findAll(active(name, ADDON_WORDS),
                PageRequest.of(0, LIMIT, Sort.by(Order.asc("monthlyFee"), Order.asc("addonServiceId")))));
    }

    /** 결합은 요금이 아니라 할인액이 있어 할인이 큰 순으로 돌려줍니다. */
    public Found<BundleProduct> searchBundles(String name) {
        return Found.of(bundleProductRepository.findAll(active(name, BUNDLE_WORDS),
                PageRequest.of(0, LIMIT, Sort.by(Order.desc("discountAmount"), Order.asc("bundleProductId")))));
    }

    /** countries가 비어 있으면 나라를 가리지 않습니다. 나라 이름은 roamingCountries()의 값 그대로여야 합니다. */
    public Found<RoamingProduct> searchRoaming(Collection<String> countries, String name) {
        Specification<RoamingProduct> specification = active(name, ROAMING_WORDS);
        if (countries != null && !countries.isEmpty()) {
            specification = specification.and((root, query, builder) -> root.get("country").in(countries));
        }
        return Found.of(roamingProductRepository.findAll(specification,
                PageRequest.of(0, LIMIT, Sort.by(Order.asc("dailyFee"), Order.asc("roamingProductId")))));
    }

    /** 판매 중인 로밍 상품이 있는 나라입니다. 이름순이고 중복이 없습니다. */
    public List<String> roamingCountries() {
        return roamingProductRepository.findAll(ProductSpecification.<RoamingProduct>filter(null, MasterStatus.ACTIVE, false))
                .stream().map(RoamingProduct::getCountry).filter(StringUtils::hasText).map(String::strip)
                .distinct().sorted().toList();
    }

    /** ACTIVE·미삭제 상품입니다. 이름은 공백을 무시한 부분 일치로 찾습니다(nameKeyword 참고). */
    private static <T extends AdminManagedEntity> Specification<T> active(String name, List<String> genericWords) {
        Specification<T> specification = ProductSpecification.filter(null, MasterStatus.ACTIVE, false);
        String keyword = nameKeyword(name, genericWords);
        if (keyword == null) {
            return specification;
        }
        String pattern = "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return specification.and((root, query, builder) -> builder.like(
                builder.lower(builder.function("replace", String.class, root.get("name"),
                        builder.literal(" "), builder.literal(""))),
                pattern, '\\'));
    }

    /**
     * LLM이 넣은 이름을 DB 이름과 비교할 형태로 바꿉니다. 작은 모델은 "밸런스 59 요금제", "밸런스59"처럼
     * 종류 이름을 붙이거나 띄어쓰기를 바꿔 넣으므로, 종류별 일반 단어와 "Ubot"을 떼고 공백을 모두 지워 소문자로 비교합니다.
     * 일반 단어는 종류마다 다릅니다. 로밍 상품명에는 "로밍"이, 결합 상품명에는 "결합"이 들어 있어
     * 그 말을 남기면 "일본 로밍"이 "Ubot 로밍 일본 베이직"과 어순이 달라 걸리지 않습니다. 남는 말이 없으면 null입니다.
     */
    static String nameKeyword(String name, List<String> genericWords) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (String token : name.strip().split("\\s+")) {
            String word = token.toLowerCase(Locale.ROOT).startsWith(BRAND) ? token.substring(BRAND.length()) : token;
            if (genericWords.contains(word)) {
                continue;
            }
            // "밸런스59요금제"처럼 붙여 쓴 경우 끝의 일반 단어 하나만 떼어 냅니다. 긴 말부터 보므로 "부가서비스"가 통째로 떨어집니다.
            for (String generic : genericWords) {
                if (word.length() > generic.length() && word.endsWith(generic)) {
                    word = word.substring(0, word.length() - generic.length());
                    break;
                }
            }
            text.append(word);
        }
        String keyword = text.toString().toLowerCase(Locale.ROOT);
        return keyword.isEmpty() ? null : keyword;
    }
}
