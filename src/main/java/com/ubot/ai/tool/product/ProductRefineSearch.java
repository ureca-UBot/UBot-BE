package com.ubot.ai.tool.product;

import com.ubot.addon.enums.AddonCategory;
import com.ubot.ai.dto.ProductDataCards;
import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.product.ProductDataRecorder.SearchRecord;
import com.ubot.bundle.enums.BundleType;
import com.ubot.plan.enums.PlanTargetGroup;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 버튼으로 고른 조건(ProductRefine)으로 상품을 다시 조회합니다. LLM 없이 상품 조회 도구를 바로 불러,
 * 조건 해석·목록 답변·카드를 LLM 경로와 같은 코드로 만듭니다. 결과를 LLM에 넣지 않으므로 LIMIT개까지 돌려주고,
 * 그보다 많거나 0건이면 고른 값이 선택된 선택지를 다시 붙입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductRefineSearch {

    /** 한 번에 돌려줄 최대 상품 수입니다. 첫 답변(LLM 경로)과 같아서, 조건을 바꾸지 않고 다시 조회하면 같은 기준으로 답합니다. */
    public static final int LIMIT = ProductCatalogQuery.LIMIT;
    static final String NOT_FOUND_ANSWER = "조건에 맞는 %s%s 없습니다. 조건을 바꿔 다시 골라 주세요.";

    private final PlanTools planTools;
    private final AddonTools addonTools;
    private final BundleTools bundleTools;
    private final RoamingTools roamingTools;
    private final ProductCatalogQuery productCatalogQuery;

    public record Result(String answer, ProductDataCards productData) {
    }

    /**
     * keyword는 이름 조건이고, conditions는 ProductRefine 묶음 키별 값입니다. 값이 비어 있으면 그 조건을 걸지 않습니다("상관없음").
     *
     * @throws InvalidConditionException 그 종류에 없는 키이거나 해석할 수 없는 값일 때
     * @throws LookupFailedException     DB 조회에 실패했을 때
     */
    public Result search(ProductType type, String keyword, Map<String, String> conditions) {
        Map<String, String> given = conditions == null ? Map.of() : conditions;
        checkKeys(type, given.keySet());
        ProductDataRecorder recorder = ProductDataRecorder.forRefine(LIMIT);
        ToolContext context = new ToolContext(Map.of(ProductDataRecorder.CONTEXT_KEY, recorder));
        String name = StringUtils.hasText(keyword) ? keyword.strip() : null;
        String result = switch (type) {
            case PLAN -> {
                String data = value(given, ProductRefine.DATA);
                boolean unlimited = ProductRefine.DATA_UNLIMITED.equals(data);
                yield planTools.searchPlans(name, value(given, ProductRefine.NETWORK), value(given, ProductRefine.TARGET),
                        number(given, ProductRefine.MAX_FEE), unlimited ? null : number(given, ProductRefine.DATA),
                        unlimited ? Boolean.TRUE : null, value(given, ProductRefine.SORT), true, context);
            }
            case ADDON -> addonTools.searchAddons(name, value(given, ProductRefine.CATEGORY), true, context);
            case BUNDLE -> bundleTools.searchBundles(name, value(given, ProductRefine.BUNDLE_TYPE),
                    number(given, ProductRefine.LINE_COUNT), true, context);
            case ROAMING -> roamingTools.searchRoaming(value(given, ProductRefine.COUNTRY), name, true, context);
        };
        Optional<SearchRecord> record = recorder.search();
        if (record.isEmpty()) {
            // 도구가 조회하지 않고 문구만 돌려줬습니다. DB 오류이거나, 값을 해석할 수 없어 다시 호출하라는 문구입니다.
            if (ProductToolResults.LOOKUP_FAILED_MESSAGE.equals(result)) {
                throw new LookupFailedException();
            }
            throw new InvalidConditionException(result);
        }
        SearchRecord searched = record.get();
        log.info("버튼 조건 상품 조회: type={}, keyword={}, conditions={}, 결과={}건", type, name, given, searched.total());
        if (searched.listing() == null) {
            String label = label(type);
            return new Result(String.format(NOT_FOUND_ANSWER, label, subjectParticle(label)),
                    new ProductDataCards(List.of(), recorder.refine().orElse(null)));
        }
        List<ProductCard> products = searched.listing().products();
        return new Result(searched.listing().answer(), new ProductDataCards(products, recorder.refine().orElse(null)));
    }

    /**
     * 단계별 선택에서 key 묶음의 선택지마다 결과가 있는지 알려 줍니다. conditions는 앞 단계까지 고른 값입니다.
     * 화면은 결과가 없는 선택지를 고를 수 없게 해서 0건 조합으로 다음 단계에 가지 못하게 합니다.
     * 값 해석은 search가 도구에 넘기는 것과 같게 해서, 있다고 답한 선택지는 실제 조회에서도 결과가 나옵니다.
     * 다만 부가서비스 도구의 "이름으로 못 찾으면 그 안의 상품 이름으로 다시 찾기"는 하지 않아 없다고 답할 수 있습니다.
     * 정렬은 결과 수를 바꾸지 않아 선택지마다 따로 세지 않습니다.
     *
     * @throws InvalidConditionException 그 종류에 없는 키이거나, key가 conditions에도 있거나, 해석할 수 없는 값일 때
     * @throws LookupFailedException     DB 조회에 실패했을 때
     */
    public ProductRefine.Availability options(ProductType type, String keyword, Map<String, String> conditions, String key) {
        Map<String, String> given = conditions == null ? Map.of() : conditions;
        checkKeys(type, given.keySet());
        if (!ProductRefine.KEYS.get(type).contains(key) || given.containsKey(key)) {
            throw new InvalidConditionException("'" + key + "'은(는) 고를 단계가 아닙니다.");
        }
        String name = StringUtils.hasText(keyword) ? keyword.strip() : null;
        try {
            List<String> sold = type == ProductType.ROAMING ? productCatalogQuery.roamingCountries() : List.of();
            ProductRefine.Group group = ProductRefine.of(new ProductSearch(type, name, given, sold), 0).groups().stream()
                    .filter(each -> each.key().equals(key)).findFirst().orElseThrow();
            boolean anyAvailable = count(type, name, given, sold) > 0;
            List<ProductRefine.OptionAvailability> options = group.options().stream()
                    .map(option -> new ProductRefine.OptionAvailability(option.value(), anyAvailable
                            && (key.equals(ProductRefine.SORT) || count(type, name, with(given, key, option.value()), sold) > 0)))
                    .toList();
            log.info("버튼 조건 단계 선택지 확인: type={}, keyword={}, conditions={}, key={}, 가능={}", type, name, given, key,
                    options.stream().filter(ProductRefine.OptionAvailability::available).count());
            return new ProductRefine.Availability(key, anyAvailable, options);
        } catch (DataAccessException exception) {
            log.warn("버튼 조건 단계 선택지 확인 실패: type={}, key={}", type, key, exception);
            throw new LookupFailedException();
        }
    }

    /** search가 도구로 조회할 조건과 같은 조건의 상품 수입니다. */
    private long count(ProductType type, String name, Map<String, String> conditions, List<String> sold) {
        try {
            return switch (type) {
                case PLAN -> {
                    String data = value(conditions, ProductRefine.DATA);
                    boolean unlimited = ProductRefine.DATA_UNLIMITED.equals(data);
                    // 정렬은 결과 수와 상관없지만 잘못된 값은 search와 같이 거절합니다.
                    ProductToolResults.parseEnum(PlanSort.class, value(conditions, ProductRefine.SORT), ProductRefine.SORT);
                    yield productCatalogQuery.countPlans(new ProductCatalogQuery.PlanCondition(name,
                            PlanTools.parseNetwork(value(conditions, ProductRefine.NETWORK)),
                            ProductToolResults.parseEnum(PlanTargetGroup.class, value(conditions, ProductRefine.TARGET),
                                    ProductRefine.TARGET),
                            number(conditions, ProductRefine.MAX_FEE), unlimited ? null : number(conditions, ProductRefine.DATA),
                            unlimited ? Boolean.TRUE : null, null));
                }
                case ADDON -> productCatalogQuery.countAddons(name, ProductToolResults.parseEnum(AddonCategory.class,
                        value(conditions, ProductRefine.CATEGORY), ProductRefine.CATEGORY));
                case BUNDLE -> productCatalogQuery.countBundles(name, ProductToolResults.parseEnum(BundleType.class,
                        value(conditions, ProductRefine.BUNDLE_TYPE), ProductRefine.BUNDLE_TYPE),
                        number(conditions, ProductRefine.LINE_COUNT));
                case ROAMING -> {
                    String country = value(conditions, ProductRefine.COUNTRY);
                    List<String> countries = country == null ? List.of() : RoamingTools.countriesIn(country, sold);
                    if (country != null && countries.isEmpty()) {
                        throw new InvalidConditionException("'" + country + "'은(는) 판매 국가가 아닙니다.");
                    }
                    yield productCatalogQuery.countRoaming(countries, name);
                }
            };
        } catch (ProductToolResults.InvalidValueException exception) {
            throw new InvalidConditionException(exception.getMessage());
        }
    }

    private static Map<String, String> with(Map<String, String> conditions, String key, String value) {
        Map<String, String> added = new LinkedHashMap<>(conditions);
        added.put(key, value);
        return added;
    }

    private static void checkKeys(ProductType type, Collection<String> keys) {
        List<String> allowed = ProductRefine.KEYS.get(type);
        for (String key : keys) {
            if (!allowed.contains(key)) {
                throw new InvalidConditionException("'" + key + "'은(는) " + type + " 조건이 아닙니다.");
            }
        }
    }

    private static String value(Map<String, String> conditions, String key) {
        String value = conditions.get(key);
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static Integer number(Map<String, String> conditions, String key) {
        String value = value(conditions, key);
        if (value == null) {
            return null;
        }
        try {
            int number = Integer.parseInt(value);
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 같은 예외로 알립니다.
        }
        throw new InvalidConditionException("'" + value + "'은(는) " + key + "에 쓸 수 없는 값입니다.");
    }

    private static String label(ProductType type) {
        return switch (type) {
            case PLAN -> PlanTools.PRODUCT_LABEL;
            case ADDON -> AddonTools.PRODUCT_LABEL;
            case BUNDLE -> BundleTools.PRODUCT_LABEL;
            case ROAMING -> RoamingTools.PRODUCT_LABEL;
        };
    }

    /** 받침이 있으면 "이", 없으면 "가"입니다. */
    static String subjectParticle(String word) {
        return ProductListing.topicParticle(word).equals("은") ? "이" : "가";
    }

    /** 버튼 조건의 키나 값이 올바르지 않습니다. 메시지는 로그용입니다. */
    public static class InvalidConditionException extends RuntimeException {
        public InvalidConditionException(String message) {
            super(message);
        }
    }

    /** 상품 조회 중 DB 오류가 났습니다. */
    public static class LookupFailedException extends RuntimeException {
        LookupFailedException() {
            super("상품 조회에 실패했습니다.");
        }
    }
}
