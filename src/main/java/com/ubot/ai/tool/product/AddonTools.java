package com.ubot.ai.tool.product;

import com.ubot.addon.entity.AddonService;
import com.ubot.addon.enums.AddonCategory;
import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import com.ubot.ai.tool.product.ProductToolResults.InvalidValueException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** LLM이 부가서비스 정보가 필요할 때 호출하는 부가서비스 조회 도구입니다. 분류 조건은 요금제 도구처럼 문자열로 받아 너그럽게 해석합니다. */
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
            @ToolParam(description = "부가서비스 분류. DATA_CHARGE(데이터 충전), VOICE_CHARGE(음성 충전), CONTENT(음악·영상·도서), "
                    + "SAFETY(자녀 보호·가족 안심·피싱 방지), CALL(발신번호 표시·통화 대기·착신전환·컬러링 등 통화 편의), "
                    + "BLOCKING(스팸·결제·국제전화·로밍 차단), ETC(기타). 사용자가 분류를 말하지 않았으면 비운다.",
                    required = false) String category,
            @ToolParam(description = ProductToolResults.LIST_REQUESTED_DESCRIPTION, required = false) Boolean listRequested,
            ToolContext toolContext) {
        AddonCategory addonCategory;
        try {
            addonCategory = ProductToolResults.parseEnum(AddonCategory.class, category, "category");
        } catch (InvalidValueException exception) {
            log.info("부가서비스 조회 도구 조건 오류: {}", exception.getMessage());
            return exception.getMessage();
        }
        ProductDataRecorder recorder = ProductToolResults.recorder(toolContext);
        try {
            ProductCatalogQuery.Found<AddonService> found = productCatalogQuery.searchAddons(name, addonCategory, recorder.limit());
            log.info("부가서비스 조회 도구: name={}, category={}, listRequested={}, 결과={}건 중 {}건", name, category,
                    listRequested, found.total(), found.items().size());
            // "V컬러링"처럼 상품 이름("Ubot 컬러링") 앞뒤에 다른 말이 붙으면 부분 일치로 찾지 못하므로,
            // 검색어 안에 들어 있는 부가서비스 이름이 하나면 그 이름으로 다시 찾습니다.
            if (found.total() == 0 && StringUtils.hasText(name)) {
                List<String> within = productNameIndex.namesMentionedIn(name, ProductType.ADDON);
                if (within.size() == 1) {
                    log.info("부가서비스 조회 도구: 이름 '{}'로 찾지 못해 그 안의 상품 이름 '{}'로 다시 찾습니다", name, within.getFirst());
                    name = within.getFirst();
                    found = productCatalogQuery.searchAddons(name, addonCategory, recorder.limit());
                }
            }
            String displayName = ProductCatalogQuery.displayName(name, ProductCatalogQuery.ADDON_WORDS);
            // 이름이 있으면 분류 말을 빼서 "데이터 충전 데이터 부가서비스"처럼 겹치지 않게 합니다.
            return ProductToolResults.format(PRODUCT_LABEL, "월 요금 낮은 순", found, AddonTools::line,
                    ProductDescriptions::of, this::card, recorder,
                    ProductSearch.of(ProductType.ADDON, name, ProductRefine.CATEGORY, addonCategory),
                    ProductToolResults.listSubject(true, PRODUCT_LABEL, false,
                            displayName == null && addonCategory != null ? categoryLabel(addonCategory) : null,
                            displayName), listRequested);
        } catch (RuntimeException exception) {
            // 도구 안의 DB 오류가 답변 전체 실패로 번지지 않게 안내 문구로 돌려줍니다.
            log.warn("부가서비스 조회 도구 실패: name={}, category={}", name, category, exception);
            return ProductToolResults.LOOKUP_FAILED_MESSAGE;
        }
    }

    private static String line(AddonService addon) {
        return ToolFormats.valueOrUnknown(addon.getName())
                + " | 월 요금 " + ToolFormats.won(addon.getMonthlyFee());
    }

    /** 부가서비스는 요금 말고 수치 컬럼이 없어 카드에 설명의 고유 문장만 담습니다. */
    private ProductCard card(AddonService addon) {
        return new ProductCard(ProductType.ADDON, addon.getName(), addon.getMonthlyFee(), List.of(),
                ProductDescriptions.highlights(ProductDescriptions.of(addon), productNameIndex.commonSentences(ProductType.ADDON)));
    }

    static String categoryLabel(AddonCategory category) {
        return switch (category) {
            case DATA_CHARGE -> "데이터 충전";
            case VOICE_CHARGE -> "음성 충전";
            case CONTENT -> "콘텐츠";
            case SAFETY -> "안심·보호";
            case CALL -> "통화";
            case BLOCKING -> "차단";
            case ETC -> "기타";
        };
    }
}
