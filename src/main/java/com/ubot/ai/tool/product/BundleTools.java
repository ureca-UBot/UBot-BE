package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import com.ubot.ai.tool.product.ProductToolResults.InvalidValueException;
import com.ubot.bundle.entity.BundleProduct;
import com.ubot.bundle.enums.BundleType;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * LLM이 결합 상품 정보가 필요할 때 호출하는 결합 상품 조회 도구입니다. 결합은 요금 대신 월 할인액이 있습니다.
 * 홈 결합은 필요한 인터넷 상품 이름을 행과 카드에 함께 씁니다. 종류 조건은 요금제 도구처럼 문자열로 받아 너그럽게 해석합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BundleTools {

    static final String PRODUCT_LABEL = "결합 상품";
    static final String HOME_PRODUCT_PREFIX = "필요 인터넷 ";

    private final ProductCatalogQuery productCatalogQuery;
    private final ProductNameIndex productNameIndex;

    @Tool(description = "Ubot 결합 상품의 월 할인액과 가입 조건을 조회한다. 결과는 최대 5개이며 할인액이 큰 순이다.")
    public String searchBundles(
            @ToolParam(description = "결합 상품 이름 일부. 예: 가족, 홈, 듀오. 사용자가 이름을 말하지 않았으면 비운다.",
                    required = false) String name,
            @ToolParam(description = "결합 종류. FAMILY(가족 n인·가족 오래함께), HOME(휴대폰+인터넷 홈 결합), "
                    + "DUO(프리미엄·청년 듀오, 부모님 함께, 우리아이 함께), ETC(친구·워치·태블릿 결합). "
                    + "사용자가 종류를 말하지 않았으면 비운다.", required = false) String bundleType,
            @ToolParam(description = "결합할 회선 수. 예: 4명이 결합하면 4. 사용자가 인원이나 회선 수를 말하지 않았으면 비운다.",
                    required = false) Integer lineCount,
            @ToolParam(description = ProductToolResults.LIST_REQUESTED_DESCRIPTION, required = false) Boolean listRequested,
            ToolContext toolContext) {
        BundleType type;
        try {
            type = ProductToolResults.parseEnum(BundleType.class, bundleType, "bundleType");
        } catch (InvalidValueException exception) {
            log.info("결합 상품 조회 도구 조건 오류: {}", exception.getMessage());
            return exception.getMessage();
        }
        ProductDataRecorder recorder = ProductToolResults.recorder(toolContext);
        try {
            // "3인 결합 상품"에 모델이 FAMILY를 채우면 친구 3인 결합이 빠지므로, 질문에 근거 말이 없는 조건은 뺍니다(ConditionGrounding).
            String said = recorder.conditionText();
            Integer lines = lineCount;
            List<String> dropped = new ArrayList<>();
            if (said != null) {
                if (type != null && !ConditionGrounding.mentionsBundleType(said, type)) {
                    dropped.add("bundleType");
                    type = null;
                }
                if (lines != null && !ConditionGrounding.mentionsLineCount(said)) {
                    dropped.add("lineCount");
                    lines = null;
                }
            }
            if (!dropped.isEmpty()) {
                log.info("결합 상품 조회 도구: 질문에 근거가 없는 조건을 뺐습니다: {}", dropped);
            }
            ProductCatalogQuery.Found<BundleProduct> found = productCatalogQuery.searchBundles(name, type, lines, recorder.limit());
            log.info("결합 상품 조회 도구: name={}, bundleType={}, lineCount={}, listRequested={}, 결과={}건 중 {}건",
                    name, bundleType, lineCount, listRequested, found.total(), found.items().size());
            String displayName = ProductCatalogQuery.displayName(name, ProductCatalogQuery.BUNDLE_WORDS);
            // 회선 수는 목록 주어에 드러나지 않아 "조건에 맞는"을 붙이고, 이름이 있으면 종류 말은 빼서 겹치지 않게 합니다.
            return ProductToolResults.format(PRODUCT_LABEL, "할인액 큰 순", found, BundleTools::line,
                    ProductDescriptions::of, this::card, recorder,
                    ProductSearch.of(ProductType.BUNDLE, name, ProductRefine.BUNDLE_TYPE, type, ProductRefine.LINE_COUNT, lines),
                    ProductToolResults.listSubject(listRequested, PRODUCT_LABEL, lines != null,
                            displayName == null && type != null ? typeLabel(type) : null, displayName));
        } catch (RuntimeException exception) {
            // 도구 안의 DB 오류가 답변 전체 실패로 번지지 않게 안내 문구로 돌려줍니다.
            log.warn("결합 상품 조회 도구 실패: name={}, bundleType={}, lineCount={}", name, bundleType, lineCount, exception);
            return ProductToolResults.LOOKUP_FAILED_MESSAGE;
        }
    }

    private static String line(BundleProduct bundle) {
        String homeProduct = homeProduct(bundle);
        return ToolFormats.valueOrUnknown(bundle.getName())
                + " | 결합 전체 월 " + ToolFormats.won(bundle.getDiscountAmount()) + " 할인"
                + (homeProduct == null ? "" : " | " + homeProduct);
    }

    /** 결합은 할인액 말고 수치 컬럼이 없어 카드에 홈 결합의 필요 인터넷과 설명의 고유 문장(가입 조건 등)만 담습니다. */
    private ProductCard card(BundleProduct bundle) {
        String homeProduct = homeProduct(bundle);
        return new ProductCard(ProductType.BUNDLE, bundle.getName(), bundle.getDiscountAmount(),
                homeProduct == null ? List.of() : List.of(homeProduct),
                ProductDescriptions.highlights(ProductDescriptions.of(bundle), productNameIndex.commonSentences(ProductType.BUNDLE)));
    }

    /** 홈 결합에 필요한 인터넷 상품입니다. 인터넷 요금은 결합 할인과 별개라 이름만 씁니다. 연결된 상품이 없으면 null입니다. */
    private static String homeProduct(BundleProduct bundle) {
        return bundle.getHomeProduct() == null ? null : HOME_PRODUCT_PREFIX + bundle.getHomeProduct().getName();
    }

    static String typeLabel(BundleType type) {
        return switch (type) {
            case FAMILY -> "가족";
            case HOME -> "홈";
            case DUO -> "듀오";
            case ETC -> "기타";
        };
    }
}
