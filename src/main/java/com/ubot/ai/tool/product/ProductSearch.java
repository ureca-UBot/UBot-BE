package com.ubot.ai.tool.product;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 상품 조회 도구가 실제로 적용한 조건입니다. LLM이 넣은 인자가 아니라, 시리즈 이름 보정·근거 없는 조건 제외까지 마친 값입니다.
 * 버튼 조건 선택(ProductRefine)이 이 값을 미리 선택된 상태로 보여 주고, 사용자가 고른 값과 합쳐 다시 조회합니다.
 *
 * @param keyword    이름 조건입니다. 버튼 선택지에 없는 조건이라 화면은 "검색어" 칩으로 보여 줍니다. 없으면 null입니다.
 * @param conditions 선택지 묶음 키(ProductRefine 참고)별 값입니다. 걸지 않은 조건은 들어 있지 않습니다.
 * @param countries  로밍의 나라 선택지(판매 국가)입니다. 다른 종류는 비어 있습니다.
 */
public record ProductSearch(ProductType type, String keyword, Map<String, String> conditions, List<String> countries) {

    public ProductSearch {
        conditions = conditions == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(conditions));
        countries = countries == null ? List.of() : List.copyOf(countries);
    }

    /** 값이 null인 조건은 빼고 넣은 순서대로 담습니다. keyValues는 키, 값, 키, 값 순입니다. */
    static ProductSearch of(ProductType type, String keyword, Object... keyValues) {
        return withCountries(type, keyword, List.of(), keyValues);
    }

    /** 로밍처럼 나라 선택지가 있는 조회입니다. */
    static ProductSearch withCountries(ProductType type, String keyword, List<String> countries, Object... keyValues) {
        Map<String, String> conditions = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            Object value = keyValues[index + 1];
            if (value != null) {
                conditions.put((String) keyValues[index], value instanceof Enum<?> constant ? constant.name() : value.toString());
            }
        }
        return new ProductSearch(type, keyword == null || keyword.isBlank() ? null : keyword.strip(), conditions, countries);
    }
}
