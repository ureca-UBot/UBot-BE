package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.ToolFormats;
import com.ubot.ai.tool.product.ProductCatalogQuery.Found;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.util.StringUtils;

/**
 * 상품 조회 도구 4개가 같이 쓰는 결과 문구와 조건 값 해석입니다. 도구 결과는 호출을 정한 뒤에 읽으므로 여기 둔 안내는 호출 여부에 영향을 주지 않습니다.
 * 조회한 상품은 도구가 LLM에 돌려준 행 그대로 ProductDataRecorder에 남겨 출력 검사 근거와 화면용 카드로 씁니다.
 */
final class ProductToolResults {

    static final String LOOKUP_FAILED_MESSAGE = "상품 정보를 조회하지 못했습니다. 상품 이름이나 금액을 추측하지 말고, "
            + "잠시 후 다시 물어봐 달라고 안내하세요. 질문의 다른 부분은 그대로 답하세요.";
    static final String NOT_FOUND_MESSAGE = "조건에 맞는 %s이(가) 없습니다. 상품 이름이나 금액을 추측하지 말고, "
            + "조건을 바꿔 다시 물어봐 달라고 안내하세요.";
    static final String MORE_RESULTS_MESSAGE = "조건에 맞는 %s이(가) 더 있습니다. 나머지를 원하면 조건을 좁혀 물어봐 달라고 안내하세요.";
    static final String COMMON_LINE = "아래 %s 모두 공통: %s";
    // LLM에 돌려주는 최대 행 수입니다. 요금제 행은 설명까지 붙어 길어서, 많이 넣으면 4096 문맥을 넘어 답변 전체가 실패합니다.
    // 조회는 ProductCatalogQuery.LIMIT개까지 하고, 목록 답변·카드·출력 검사 근거에는 조회한 행을 모두 씁니다.
    static final int LLM_ROWS = 5;
    static final String INVALID_VALUE_MESSAGE = "'%s'은(는) %s에 쓸 수 없는 값입니다. %s 중 하나를 넣거나 비워서 다시 호출하세요.";
    // 네 도구가 같은 뜻으로 받는 목록 요청 표시입니다. 표시가 있고 상품이 2개 이상이면 답변을 코드가 목록으로 만듭니다.
    static final String LIST_REQUESTED_DESCRIPTION = "사용자가 상품 목록이나 종류를 물으면 true. "
            + "특정 상품 하나의 정보를 물으면 비운다.";
    // 1: "하나·둘·셋·넷" 단독 표현. 뒤에 조사·다른 글자가 붙은 "하나로", "넷플릭스"는 제외합니다.
    // 2: "한·두·세·네" 또는 숫자 1~4 뒤에 "개·가지". "1개월"은 제외합니다.
    // 상품 하나를 고르는 질문의 말입니다. "제일 싼 거", "데이터 가장 많은 요금제", "추천해 줘"는 위 행만으로 답할 수 있어 목록으로 바꾸지 않습니다.
    private static final Pattern PICK = Pattern.compile("제일|가장|젤|최저|최고|최대|최소|추천");
    private static final Pattern REQUESTED_COUNT = Pattern.compile(
            "(?<![가-힣A-Za-z0-9])(?:(하나|둘|셋|넷)(?=만|\\s|$|[.?!,]|알려|추천|골라|보여)"
                    + "|(한|두|세|네|[1-4])\\s*(?:개|가지)(?!월))");

    private ProductToolResults() {
    }

    /** AiService가 넣은 기록 보관소입니다. 평가처럼 넣지 않은 호출에서는 버려지는 보관소를 씁니다. */
    static ProductDataRecorder recorder(ToolContext toolContext) {
        return toolContext != null && toolContext.getContext().get(ProductDataRecorder.CONTEXT_KEY)
                instanceof ProductDataRecorder value ? value : new ProductDataRecorder();
    }

    /**
     * "조건에 맞는 요금제 12건 중 5건(월정액 낮은 순)" 머리말과 상품 행을 만들고, 돌려준 상품을 기록합니다.
     * 행 끝에는 다듬은 설명 문장(description, ProductDescriptions.of)을 붙입니다. 상품 설명에는 "표시 월정액은 부가세 포함"처럼
     * 같은 종류 상품에 똑같이 들어간 문장이 많아, 돌려준 상품 모두에 있는 문장은 행마다 쓰지 않고 "공통" 줄에 한 번만 씁니다.
     * 조건에 맞는 상품이 LLM에 돌려준 행보다 많으면 조건을 좁혀 달라고 안내하게 합니다.
     * subject는 목록 답변·조건 선택 안내의 주어이고(listSubject 참고), 목록 답변을 만들면 안 되는 조회면 null입니다.
     * LLM이 목록 요청이라고 표시했고(listRequested) 상품이 2개 이상이면 AiService가 목록 답변을 만들 수 있게 기록합니다.
     * 결과가 상한을 넘으면 표시와 상관없이 조건 선택 안내를 기록합니다(promptsRefine 참고).
     * search는 도구가 실제로 적용한 조건으로, 0건이어도 함께 기록해 버튼 조건 선택(ProductRefine)에 씁니다.
     */
    static <T> String format(String productLabel, String order, Found<T> found, Function<T, String> line,
            Function<T, List<String>> description, Function<T, ProductCard> card, ProductDataRecorder recorder,
            ProductSearch search, String subject, Boolean listRequested) {
        return format(productLabel, order, found, line, description, card, recorder, search, subject, listRequested, null);
    }

    /**
     * 돌려준 상품 대신 다른 목록 답변을 기록할 때 씁니다. 요금제 도구가 조건 없는 목록 요청에 시리즈 개요(PlanSeriesOverview)를 넣습니다.
     * LLM에 돌려주는 결과는 조회한 상품 행 그대로이고, 목록 답변에만 있는 수치는 호출한 쪽이 recorder.note로 출력 검사 근거에 남깁니다.
     */
    static <T> String format(String productLabel, String order, Found<T> found, Function<T, String> line,
            Function<T, List<String>> description, Function<T, ProductCard> card, ProductDataRecorder recorder,
            ProductSearch search, ProductListAnswer listAnswer) {
        return format(productLabel, order, found, line, description, card, recorder, search, null, true, listAnswer);
    }

    private static <T> String format(String productLabel, String order, Found<T> found, Function<T, String> line,
            Function<T, List<String>> description, Function<T, ProductCard> card, ProductDataRecorder recorder,
            ProductSearch search, String subject, Boolean listRequested, ProductListAnswer listAnswer) {
        if (found.items().isEmpty()) {
            recorder.searched(new ProductDataRecorder.SearchRecord(search, 0, null));
            return String.format(NOT_FOUND_MESSAGE, productLabel);
        }
        List<T> items = found.items();
        // LLM에는 앞의 LLM_ROWS행만 돌려줍니다. 나머지 행도 출력 검사 근거와 목록 답변·카드에는 씁니다.
        int shown = Math.min(items.size(), LLM_ROWS);
        StringBuilder text = new StringBuilder();
        text.append("조건에 맞는 ").append(productLabel).append(' ').append(found.total()).append("건 중 ")
                .append(shown).append("건(").append(order).append(")\n");
        List<List<String>> sentences = items.stream().map(description).toList();
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
            if (index < shown) {
                text.append(row).append('\n');
            }
        }
        if (found.total() > shown) {
            text.append(String.format(MORE_RESULTS_MESSAGE, productLabel));
        }
        // "하나만 알려줘"처럼 개수를 말했으면 목록 답변과 카드만 그 개수로 줄입니다. LLM에 돌려준 행과 검사 근거는 그대로입니다.
        OptionalInt requested = requestedCount(recorder.question());
        long listed = requested.orElse(items.size());
        // 버튼 조건 조회는 LLM 답변이 없어 상품이 하나여도 목록 답변으로 답합니다.
        int listMinimum = recorder.refineSearch() ? 1 : 2;
        ProductListAnswer listing = listAnswer != null ? listAnswer
                : !Boolean.TRUE.equals(listRequested) || subject == null || items.size() < listMinimum ? null
                : new ProductListing(subject, order, found.total(), items.stream().limit(listed).map(card).toList());
        // 결과가 다 들어가지 않으면 첫 답변은 목록 대신 조건을 고르라고 안내합니다. 선택지는 AiService가 붙입니다.
        // 작은 모델은 "무제한 요금제 알려줘"에도 목록 요청 표시를 빠뜨리므로 표시와 상관없이 판단합니다.
        if (promptsRefine(recorder, found.total())) {
            if (listAnswer != null) {
                listing = ProductRefinePrompt.of(listAnswer, found.total());
            } else if (subject != null) {
                listing = ProductRefinePrompt.of(subject, found.total());
            }
        }
        recorder.searched(new ProductDataRecorder.SearchRecord(search, found.total(), listing));
        return text.toString().stripTrailing();
    }

    /**
     * 목록 답변의 주어입니다. 목록 요청이 아니면 null입니다.
     * 이름·네트워크 같은 조건 말을 종류 이름 앞에 붙이고("프리미엄 요금제", "5G 청년 요금제"),
     * 주어에 드러나지 않는 조건(월정액 상한 등)이 있거나 붙일 말이 없으면 "조건에 맞는"을 앞에 둡니다.
     */
    static String listSubject(Boolean listRequested, String productLabel, boolean hiddenConditions,
            String... qualifiers) {
        if (!Boolean.TRUE.equals(listRequested)) {
            return null;
        }
        List<String> qualifierWords = Arrays.stream(qualifiers).filter(StringUtils::hasText).map(String::strip)
                .distinct().toList();
        // 대상 "시니어"와 이름 "시니어 안심"처럼 다른 조건 말에 이미 들어 있는 말은 한 번만 씁니다.
        List<String> words = qualifierWords.stream()
                .filter(word -> qualifierWords.stream().noneMatch(other -> !other.equals(word)
                        && Arrays.asList(other.split("\\s+")).contains(word)))
                .toList();
        String subject = words.isEmpty() ? productLabel : String.join(" ", words) + " " + productLabel;
        return hiddenConditions || words.isEmpty() ? "조건에 맞는 " + subject : subject;
    }

    /**
     * 첫 답변을 목록 대신 조건 선택 안내(ProductRefinePrompt)로 바꿀지입니다. LLM 경로에서 결과가 상한을 넘으면 LLM은
     * 앞의 몇 행만 보고 답해 목록이 늘 모자라므로, 모델의 목록 요청 표시와 상관없이 바꿉니다.
     * 다만 "하나만"처럼 개수를 말했거나 "제일 싼 거"처럼 하나를 고르는 질문은 앞의 행만으로 답할 수 있어 바꾸지 않습니다.
     * 버튼 조건 조회는 목록과 선택지를 함께 보여 주므로 바꾸지 않습니다.
     */
    static boolean promptsRefine(ProductDataRecorder recorder, long total) {
        String question = recorder.question();
        return !recorder.refineSearch() && total > recorder.limit() && requestedCount(question).isEmpty()
                && (question == null || !PICK.matcher(question).find());
    }

    /**
     * 질문에서 "하나만", "두 개", "3가지"처럼 보여 달라고 한 상품 수(1~4)를 찾습니다. 없으면 비어 있습니다.
     * "1개월", "하나카드", "넷플릭스"처럼 수가 아닌 말은 앞뒤 글자를 보고 거릅니다. 결과는 최대 5개라 다섯 이상은 보지 않습니다.
     */
    static OptionalInt requestedCount(String question) {
        if (!StringUtils.hasText(question)) {
            return OptionalInt.empty();
        }
        Matcher matcher = REQUESTED_COUNT.matcher(question);
        if (!matcher.find()) {
            return OptionalInt.empty();
        }
        String word = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return OptionalInt.of(switch (word) {
            case "하나", "한", "1" -> 1;
            case "둘", "두", "2" -> 2;
            case "셋", "세", "3" -> 3;
            default -> 4;
        });
    }

    /**
     * LLM이 넣은 열거형 조건 값을 해석합니다. 비어 있으면 null이고, 대소문자와 앞뒤 공백은 가리지 않습니다.
     * 값 이름에 없는 값이면 쓸 수 있는 값을 알려 주는 재요청 문구를 담아 InvalidValueException을 던집니다.
     */
    static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String parameter) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            String allowed = String.join(", ", Arrays.stream(type.getEnumConstants()).map(Enum::name).toList());
            throw new InvalidValueException(String.format(INVALID_VALUE_MESSAGE, value, parameter, allowed));
        }
    }

    /** LLM이 넣은 조건 값을 해석할 수 없을 때 재요청 문구를 돌려주려고 씁니다. */
    static final class InvalidValueException extends RuntimeException {
        InvalidValueException(String message) {
            super(message);
        }
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
