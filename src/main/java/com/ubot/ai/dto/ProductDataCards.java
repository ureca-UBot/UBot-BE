package com.ubot.ai.dto;

import com.ubot.ai.tool.product.ProductType;
import java.util.List;

/**
 * 답변에 쓰인 상품(요금제·부가서비스·결합·로밍)을 화면이 카드로 그릴 수 있게 담습니다.
 * LLM 문장과 별도로 응답에 실리며, 목록은 비어 있을 수는 있어도 null이 아닙니다.
 */
public record ProductDataCards(List<ProductCard> products) {

    public ProductDataCards {
        products = products == null ? List.of() : List.copyOf(products);
    }

    /**
     * 화면에 보여 줄 상품 정보입니다. type은 화면이 요금 앞말(월·일·할인)을 고르는 데만 씁니다.
     * fee는 요금제·부가서비스는 월 요금, 로밍은 일 요금, 결합은 할인액이며 없으면 null입니다.
     *
     * @param specs      컬럼 값으로 만든 핵심 수치 줄로, 화면이 그대로 보여 줍니다. 요금제는 데이터·음성/문자·테더링 세 줄입니다.
     *                   예: "월 데이터 24GB, 소진 후 최대 1Mbps". 요금 말고 수치가 없는 종류는 비어 있습니다.
     * @param highlights 상품 설명에서 고른 그 상품 고유의 규칙·혜택 전부입니다. 예: "가입 대상 만 19~34세". 비어 있을 수 있습니다.
     * @param network    요금제의 네트워크("5G", "LTE")입니다. 화면은 카드 뱃지로 씁니다. 그 밖의 종류는 null입니다.
     * @param feeNote    요금제의 선택약정 할인가 문구입니다. 예: "선택약정 25% 할인 시 월 44,250원". 무약정 요금제와 그 밖의 종류는 null입니다.
     */
    public record ProductCard(ProductType type, String name, Integer fee, List<String> specs, List<String> highlights,
            String network, String feeNote) {

        public ProductCard {
            specs = specs == null ? List.of() : List.copyOf(specs);
            highlights = highlights == null ? List.of() : List.copyOf(highlights);
        }

        /** 네트워크·선택약정 문구가 없는 종류(부가서비스·결합·로밍)의 카드입니다. */
        public ProductCard(ProductType type, String name, Integer fee, List<String> specs, List<String> highlights) {
            this(type, name, fee, specs, highlights, null, null);
        }
    }
}
