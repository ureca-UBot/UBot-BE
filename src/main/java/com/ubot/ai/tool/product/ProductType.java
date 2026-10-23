package com.ubot.ai.tool.product;

import com.ubot.ai.tool.AiTool;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 상품 정보 질문에서 조회할 상품 종류입니다. 종류마다 조회 도구가 하나씩 있고,
 * FAQ 카테고리 이름이나 질문 속 키워드로 어느 종류를 조회할지 정합니다.
 */
public enum ProductType {
    PLAN(AiTool.PLAN_SEARCH, Set.of("요금제", "요금제/변경"), List.of("요금제")),
    ADDON(AiTool.ADDON_SEARCH, Set.of("부가서비스"), List.of("부가서비스", "부가 서비스")),
    BUNDLE(AiTool.BUNDLE_SEARCH, Set.of("결합상품"), List.of("결합")),
    ROAMING(AiTool.ROAMING_SEARCH, Set.of("해외 로밍", "로밍/통신망 선택"), List.of("로밍"));

    private final AiTool tool;
    private final Set<String> categories;
    private final List<String> keywords;

    ProductType(AiTool tool, Set<String> categories, List<String> keywords) {
        this.tool = tool;
        this.categories = categories;
        this.keywords = keywords;
    }

    public AiTool tool() {
        return tool;
    }

    /** FAQ 카테고리 이름에 맞는 종류입니다. 상품 카테고리가 아니면 비어 있습니다. */
    public static Optional<ProductType> fromCategory(String categoryName) {
        if (categoryName == null) {
            return Optional.empty();
        }
        String name = categoryName.strip();
        for (ProductType type : values()) {
            if (type.categories.contains(name)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** 질문에 종류를 가리키는 말("요금제", "로밍" 등)이 있는지 봅니다. */
    public boolean isMentionedIn(String text) {
        return text != null && keywords.stream().anyMatch(text::contains);
    }
}
