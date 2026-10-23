package com.ubot.ai.tool.product;

import com.ubot.addon.entity.AddonService;
import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import java.util.List;
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
    private final ProductNameIndex productNameIndex;

    @Tool(description = "Ubot 부가서비스의 월 요금과 설명을 조회한다. 결과는 최대 5개이며 월 요금이 낮은 순이다.")
    public String searchAddons(
            @ToolParam(description = "부가서비스 이름 일부. 예: 컬러링, 데이터 플러스. 사용자가 이름을 말하지 않았으면 비운다.",
                    required = false) String name,
            @ToolParam(description = ProductToolResults.LIST_REQUESTED_DESCRIPTION, required = false) Boolean listRequested,
            ToolContext toolContext) {
        ProductDataRecorder recorder = ProductToolResults.recorder(toolContext);
        try {
            ProductCatalogQuery.Found<AddonService> found = productCatalogQuery.searchAddons(name);
            log.info("부가서비스 조회 도구: name={}, listRequested={}, 결과={}건 중 {}건", name, listRequested,
                    found.total(), found.items().size());
            return ProductToolResults.format(PRODUCT_LABEL, "월 요금 낮은 순", found, AddonTools::line,
                    ProductDescriptions::of, this::card, recorder,
                    ProductToolResults.listSubject(listRequested, PRODUCT_LABEL, false,
                            ProductCatalogQuery.displayName(name, ProductCatalogQuery.ADDON_WORDS)));
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

    /** 부가서비스는 요금 말고 수치 컬럼이 없어 카드에 설명의 고유 문장만 담습니다. */
    private ProductCard card(AddonService addon) {
        return new ProductCard(ProductType.ADDON, addon.getName(), addon.getMonthlyFee(), List.of(),
                ProductDescriptions.highlights(ProductDescriptions.of(addon), productNameIndex.commonSentences(ProductType.ADDON)));
    }
}
