package com.ubot.ai.tool.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProductCatalogQueryTest {

    @Test
    void nameKeywordRemovesBrandGenericWordsAndSpaces() {
        assertThat(ProductCatalogQuery.nameKeyword("Ubot 밸런스 59 요금제", ProductCatalogQuery.PLAN_WORDS)).isEqualTo("밸런스59");
        assertThat(ProductCatalogQuery.nameKeyword("밸런스59요금제", ProductCatalogQuery.PLAN_WORDS)).isEqualTo("밸런스59");
        assertThat(ProductCatalogQuery.nameKeyword("ubot 컬러링 부가서비스", ProductCatalogQuery.ADDON_WORDS)).isEqualTo("컬러링");
        assertThat(ProductCatalogQuery.nameKeyword("가족 결합", ProductCatalogQuery.BUNDLE_WORDS)).isEqualTo("가족");
        assertThat(ProductCatalogQuery.nameKeyword("일본 로밍", ProductCatalogQuery.ROAMING_WORDS)).isEqualTo("일본");
    }

    @Test
    void genericWordsDependOnProductType() {
        // 부가서비스 "Ubot 데이터 로밍 차단"에서는 "로밍"이 이름의 일부라 떼면 안 됩니다.
        assertThat(ProductCatalogQuery.nameKeyword("데이터 로밍 차단", ProductCatalogQuery.ADDON_WORDS)).isEqualTo("데이터로밍차단");
    }

    @Test
    void nameKeywordIsNullWhenOnlyGenericWordsRemain() {
        assertThat(ProductCatalogQuery.nameKeyword("요금제", ProductCatalogQuery.PLAN_WORDS)).isNull();
        assertThat(ProductCatalogQuery.nameKeyword("Ubot 상품", ProductCatalogQuery.PLAN_WORDS)).isNull();
        assertThat(ProductCatalogQuery.nameKeyword(" ", ProductCatalogQuery.PLAN_WORDS)).isNull();
    }

    @Test
    void countriesInFindsSoldCountriesInsideInput() {
        List<String> sold = List.of("일본", "중국", "미국");

        assertThat(RoamingTools.countriesIn("일본 도쿄", sold)).containsExactly("일본");
        assertThat(RoamingTools.countriesIn("일본, 중국", sold)).containsExactly("일본", "중국");
        assertThat(RoamingTools.countriesIn("유럽", sold)).isEmpty();
    }
}
