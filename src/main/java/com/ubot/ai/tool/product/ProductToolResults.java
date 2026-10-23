package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import com.ubot.ai.tool.product.ProductCatalogQuery.Found;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 상품 조회 도구 4개가 같이 쓰는 결과 문구입니다. 도구 결과는 호출을 정한 뒤에 읽으므로 여기 둔 안내는 호출 여부에 영향을 주지 않습니다.
 * 조회한 상품은 도구가 LLM에 돌려준 행 그대로 ProductDataRecorder에 남겨 출력 검사 근거와 화면용 카드로 씁니다.
 */
final class ProductToolResults {

    static final String LOOKUP_FAILED_MESSAGE = "상품 정보를 조회하지 못했습니다. 상품 이름이나 금액을 추측하지 말고, "
            + "잠시 후 다시 물어봐 달라고 안내하세요. 질문의 다른 부분은 그대로 답하세요.";
    static final String NOT_FOUND_MESSAGE = "조건에 맞는 %s이(가) 없습니다. 상품 이름이나 금액을 추측하지 말고, "
            + "조건을 바꿔 다시 물어봐 달라고 안내하세요.";
    static final String MORE_RESULTS_MESSAGE = "조건에 맞는 %s이(가) 더 있습니다. 나머지를 원하면 조건을 좁혀 물어봐 달라고 안내하세요.";
    static final String COMMON_LINE = "아래 %s 모두 공통: %s";

    private ProductToolResults() {
    }

    /** AiService가 넣은 기록 보관소입니다. 평가처럼 넣지 않은 호출에서는 버려지는 보관소를 씁니다. */
    static ProductDataRecorder recorder(ToolContext toolContext) {
        return toolContext != null && toolContext.getContext().get(ProductDataRecorder.CONTEXT_KEY)
                instanceof ProductDataRecorder value ? value : new ProductDataRecorder();
    }

    /**
     * "조건에 맞는 요금제 12건 중 5건(월정액 낮은 순)" 머리말과 상품 행을 만들고, 돌려준 상품을 기록합니다.
     * 행 끝에는 다듬은 설명을 붙입니다(ProductDescriptions 참고). 상품 설명에는 "표시 월정액은 부가세 포함"처럼
     * 같은 종류 상품에 똑같이 들어간 문장이 많아, 돌려준 상품 모두에 있는 문장은 행마다 쓰지 않고 "공통" 줄에 한 번만 씁니다.
     * 조건에 맞는 상품이 LIMIT개보다 많으면 조건을 좁혀 달라고 안내하게 합니다.
     */
    static <T> String format(String productLabel, String order, Found<T> found, Function<T, String> line,
            Function<T, String> description, Function<T, ProductCard> card, ProductDataRecorder recorder) {
        if (found.items().isEmpty()) {
            return String.format(NOT_FOUND_MESSAGE, productLabel);
        }
        StringBuilder text = new StringBuilder();
        text.append("조건에 맞는 ").append(productLabel).append(' ').append(found.total()).append("건 중 ")
                .append(found.items().size()).append("건(").append(order).append(")\n");
        List<T> items = found.items();
        List<List<String>> sentences = items.stream()
                .map(item -> ProductDescriptions.sentences(description.apply(item))).toList();
        List<String> common = items.size() < 2 ? List.of() : sentences.getFirst().stream()
                .filter(sentence -> sentences.stream().allMatch(each -> each.contains(sentence))).toList();
        if (!common.isEmpty()) {
            String commonLine = String.format(COMMON_LINE, productLabel, String.join(" ", common));
            recorder.note(commonLine);
            text.append(commonLine).append('\n');
        }
        for (int index = 0; index < items.size(); index++) {
            T item = items.get(index);
            List<String> own = sentences.get(index).stream().filter(sentence -> !common.contains(sentence)).toList();
            String row = "- " + line.apply(item) + (own.isEmpty() ? "" : " | " + String.join(" ", own));
            recorder.record(card.apply(item), row);
            text.append(row).append('\n');
        }
        if (found.total() > items.size()) {
            text.append(String.format(MORE_RESULTS_MESSAGE, productLabel));
        }
        return text.toString().stripTrailing();
    }

    /** DB 규칙(1GB = 1000MB)대로 보여 줍니다. 1000MB 이상이면 GB로, 소수점은 필요할 때만 씁니다. */
    static String data(Long mb) {
        if (mb == null) {
            return ToolFormats.UNKNOWN;
        }
        if (mb < ProductCatalogQuery.MB_PER_GB) {
            return mb + "MB";
        }
        double gb = mb / (double) ProductCatalogQuery.MB_PER_GB;
        return gb == Math.floor(gb) ? (long) gb + "GB" : String.format(Locale.ROOT, "%.1fGB", gb);
    }
}
