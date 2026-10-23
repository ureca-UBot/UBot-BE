package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import com.ubot.bundle.entity.BundleProduct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** LLM이 결합 상품 정보가 필요할 때 호출하는 결합 상품 조회 도구입니다. 결합은 요금 대신 월 할인액이 있습니다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BundleTools {

    static final String PRODUCT_LABEL = "결합 상품";

    private final ProductCatalogQuery productCatalogQuery;
    private final ProductNameIndex productNameIndex;

    @Tool(description = "Ubot 결합 상품의 월 할인액과 가입 조건을 조회한다. 결과는 최대 5개이며 할인액이 큰 순이다.")
    public String searchBundles(
            @ToolParam(description = "결합 상품 이름 일부. 예: 가족, 홈, 듀오. 사용자가 이름을 말하지 않았으면 비운다.",
                    required = false) String name,
            @ToolParam(description = ProductToolResults.LIST_REQUESTED_DESCRIPTION, required = false) Boolean listRequested,
            ToolContext toolContext) {
        ProductDataRecorder recorder = ProductToolResults.recorder(toolContext);
        try {
            ProductCatalogQuery.Found<BundleProduct> found = productCatalogQuery.searchBundles(name);
            log.info("결합 상품 조회 도구: name={}, listRequested={}, 결과={}건 중 {}건", name, listRequested,
                    found.total(), found.items().size());
            return ProductToolResults.format(PRODUCT_LABEL, "할인액 큰 순", found, BundleTools::line,
                    ProductDescriptions::of, this::card, recorder,
                    ProductToolResults.listSubject(listRequested, PRODUCT_LABEL, false,
                            ProductCatalogQuery.displayName(name, ProductCatalogQuery.BUNDLE_WORDS)));
        } catch (RuntimeException exception) {
            // 도구 안의 DB 오류가 답변 전체 실패로 번지지 않게 안내 문구로 돌려줍니다.
            log.warn("결합 상품 조회 도구 실패: name={}", name, exception);
            return ProductToolResults.LOOKUP_FAILED_MESSAGE;
        }
    }

    private static String line(BundleProduct bundle) {
        return ToolFormats.valueOrUnknown(bundle.getName())
                + " | 결합 전체 월 " + ToolFormats.won(bundle.getDiscountAmount()) + " 할인";
    }

    /** 결합은 할인액 말고 수치 컬럼이 없어 카드에 설명의 고유 문장(가입 조건 등)만 담습니다. */
    private ProductCard card(BundleProduct bundle) {
        return new ProductCard(ProductType.BUNDLE, bundle.getName(), bundle.getDiscountAmount(), List.of(),
                ProductDescriptions.highlights(ProductDescriptions.of(bundle), productNameIndex.commonSentences(ProductType.BUNDLE)));
    }
}
