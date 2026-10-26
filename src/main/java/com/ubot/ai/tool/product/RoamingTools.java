package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import com.ubot.roaming.entity.RoamingProduct;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * LLM이 해외 로밍 상품 정보가 필요할 때 호출하는 로밍 상품 조회 도구입니다. 로밍 상품은 국가별로 따로 있습니다.
 * 결과에는 판매 국가 목록을 항상 함께 넣습니다. 상품은 최대 5개만 돌려주므로, 목록이 없으면 모델이 그 5개 나라만 있다고 오해합니다.
 * 나라 이름은 "일본 도쿄", "일본, 중국"처럼 들어와도 그 안에 있는 판매 국가로 찾고,
 * "유럽"처럼 판매 국가가 하나도 없으면 목록을 보여 주며 나라 이름으로 다시 호출하게 합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoamingTools {

    static final String PRODUCT_LABEL = "로밍 상품";
    static final String COUNTRIES_LINE = "로밍 상품을 판매하는 나라(%d곳): %s";
    static final String COUNTRY_NOT_FOUND_MESSAGE = "'%s'에 해당하는 나라의 로밍 상품이 없습니다. %s\n"
            + "사용자가 말한 지역에 이 목록의 나라가 있으면 country에 그 나라 이름을 넣어 다시 호출하고, "
            + "없으면 그 지역 전용 로밍 상품은 없다고 안내하세요.";

    private final ProductCatalogQuery productCatalogQuery;
    private final ProductNameIndex productNameIndex;

    @Tool(description = "Ubot 해외 로밍 상품의 국가, 일 요금, 데이터 제공량을 조회한다. 결과는 최대 5개이며 일 요금이 낮은 순이다. "
            + "사용자가 나라를 말했으면 country에 그 나라 이름을 넣는다. 결과에는 로밍 상품을 판매하는 나라 목록도 함께 나온다.")
    public String searchRoaming(
            @ToolParam(description = "나라 이름. 예: 일본, 미국. 사용자가 나라를 말하지 않았으면 비운다.", required = false)
            String country,
            @ToolParam(description = "로밍 상품 이름 일부. 예: 베이직, 맥스. 사용자가 이름을 말하지 않았으면 비운다.", required = false)
            String name,
            @ToolParam(description = ProductToolResults.LIST_REQUESTED_DESCRIPTION, required = false) Boolean listRequested,
            ToolContext toolContext) {
        ProductDataRecorder recorder = ProductToolResults.recorder(toolContext);
        try {
            List<String> sold = productCatalogQuery.roamingCountries();
            String countriesLine = String.format(COUNTRIES_LINE, sold.size(), String.join(", ", sold));
            List<String> countries = StringUtils.hasText(country) ? countriesIn(country, sold) : List.of();
            if (StringUtils.hasText(country) && countries.isEmpty()) {
                log.info("로밍 상품 조회 도구: country={}, name={}, 판매 국가 아님", country, name);
                return String.format(COUNTRY_NOT_FOUND_MESSAGE, country.strip(), countriesLine);
            }
            ProductCatalogQuery.Found<RoamingProduct> found = productCatalogQuery.searchRoaming(countries, name);
            log.info("로밍 상품 조회 도구: country={}, name={}, listRequested={}, 결과={}건 중 {}건", country, name,
                    listRequested, found.total(), found.items().size());
            String result = ProductToolResults.format(PRODUCT_LABEL, "일 요금 낮은 순", found, RoamingTools::line,
                    ProductDescriptions::of, this::card, recorder,
                    ProductToolResults.listSubject(listRequested, PRODUCT_LABEL, false, String.join(", ", countries),
                            ProductCatalogQuery.displayName(name, ProductCatalogQuery.ROAMING_WORDS)));
            return result + "\n" + countriesLine;
        } catch (RuntimeException exception) {
            // 도구 안의 DB 오류가 답변 전체 실패로 번지지 않게 안내 문구로 돌려줍니다.
            log.warn("로밍 상품 조회 도구 실패: country={}, name={}", country, name, exception);
            return ProductToolResults.LOOKUP_FAILED_MESSAGE;
        }
    }

    /** 입력에 들어 있는 판매 국가입니다. 공백을 무시하고 비교해 "일본도쿄"에서도 "일본"을 찾습니다. */
    static List<String> countriesIn(String input, List<String> sold) {
        String text = input.replaceAll("\\s+", "");
        return sold.stream().filter(candidate -> text.contains(candidate.replaceAll("\\s+", ""))).toList();
    }

    private static String line(RoamingProduct roaming) {
        return ToolFormats.valueOrUnknown(roaming.getName())
                + " | " + ToolFormats.valueOrUnknown(roaming.getCountry())
                + " | 1일 요금 " + ToolFormats.won(roaming.getDailyFee())
                + " | 데이터 " + ProductToolResults.data(roaming.getDataAmountMb());
    }

    /** 카드에는 나라와 데이터 제공량, 설명의 고유 문장을 담습니다. */
    private ProductCard card(RoamingProduct roaming) {
        List<String> specs = new ArrayList<>();
        if (StringUtils.hasText(roaming.getCountry())) {
            specs.add(roaming.getCountry().strip());
        }
        specs.add("데이터 " + ProductToolResults.data(roaming.getDataAmountMb()));
        return new ProductCard(ProductType.ROAMING, roaming.getName(), roaming.getDailyFee(), specs,
                ProductDescriptions.highlights(ProductDescriptions.of(roaming),
                        productNameIndex.commonSentences(ProductType.ROAMING)));
    }
}
