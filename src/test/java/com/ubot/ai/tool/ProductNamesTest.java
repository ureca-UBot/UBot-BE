package com.ubot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ProductNamesTest {

    private record Named(String type, String name) {
    }

    private static final Function<Named, String> NAME = Named::name;

    @Test
    void normalizeRemovesBrandPrefixAndSpaces() {
        assertThat(ProductNames.normalize("Ubot 컬러링")).isEqualTo("컬러링");
        assertThat(ProductNames.normalize("Ubot 실속 LTE 1GB/60분")).isEqualTo("실속LTE1GB/60분");
        assertThat(ProductNames.normalize("  ")).isEmpty();
        assertThat(ProductNames.normalize(null)).isEmpty();
    }

    @Test
    void findsNameWrittenWithoutBrandOrSpaces() {
        List<Named> items = List.of(new Named("ADDON", "Ubot 컬러링"), new Named("ADDON", "Ubot 착신전환"));

        assertThat(ProductNames.findMentioned("컬러링 월 요금이 궁금해요", items, NAME))
                .containsExactly(new Named("ADDON", "Ubot 컬러링"));
    }

    @Test
    void longerNameWinsOverShorterNameInside() {
        // 부가서비스 "북패스"는 요금제 "북패스 7GB+" 안에 들어 있습니다.
        List<Named> items = List.of(new Named("ADDON", "Ubot 북패스"), new Named("PLAN", "Ubot 북패스 7GB+"));

        assertThat(ProductNames.findMentioned("북패스 7GB+ 요금제 얼마예요?", items, NAME))
                .containsExactly(new Named("PLAN", "Ubot 북패스 7GB+"));
        assertThat(ProductNames.findMentioned("북패스 7GB+랑 북패스 부가서비스 차이", items, NAME))
                .containsExactlyInAnyOrder(new Named("PLAN", "Ubot 북패스 7GB+"), new Named("ADDON", "Ubot 북패스"));
    }

    @Test
    void sameNameOfDifferentItemsIsFoundTogether() {
        // 로밍 국가는 상품마다 같은 이름으로 들어 있습니다.
        List<Named> items = List.of(new Named("ROAMING", "일본"), new Named("ROAMING-2", "일본"));

        assertThat(ProductNames.findMentioned("일본 갈 때 로밍", items, NAME)).hasSize(2);
    }

    @Test
    void ignoresOneLetterNames() {
        assertThat(ProductNames.findMentioned("가족 결합", List.of(new Named("X", "가")), NAME)).isEmpty();
    }
}
