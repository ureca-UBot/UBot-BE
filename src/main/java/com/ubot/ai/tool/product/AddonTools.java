package com.ubot.ai.tool.product;

import com.ubot.addon.entity.AddonService;
import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** LLM이 부가서비스 정보가 필요할 때 호출하는 부가서비스 조회 도구입니다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AddonTools {

    static final String PRODUCT_LABEL = "부가서비스";

    private final ProductCatalogQuery productCatalogQuery;

    @Tool(description = "Ubot 부가서비스의 월 요금과 설명을 조회한다. 결과는 최대 5개이며 월 요금이 낮은 순이다.")
    public String searchAddons(
            @ToolParam(description = "부가서비스 이름 일부. 예: 컬러링, 데이터 플러스. 사용자가 이름을 말하지 않았으면 비운다.",
                    required = false) String name,
            ToolContext toolContext) {
        ProductDataRecorder recorder = ProductToolResults.recorder(toolContext);
        try {
            return ProductToolResults.format(PRODUCT_LABEL, "월 요금 낮은 순", productCatalogQuery.searchAddons(name),
                    AddonTools::line, AddonService::getDescription, AddonTools::card, recorder);
        } catch (RuntimeException exception) {
            // 도구 안의 DB 오류가 답변 전체 실패로 번지지 않게 안내 문구로 돌려줍니다.
            log.warn("부가서비스 조회 도구 실패: name={}", name, exception);
            return ProductToolResults.LOOKUP_FAILED_MESSAGE;
        }
    }

    private static String line(AddonService addon) {
        return ToolFormats.valueOrUnknown(addon.getName())
                + " | 월 " + ToolFormats.won(addon.getMonthlyFee());
    }

    private static ProductCard card(AddonService addon) {
        return new ProductCard(ProductType.ADDON, addon.getName(), addon.getMonthlyFee(), addon.getDescription());
    }
}
