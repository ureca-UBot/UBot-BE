package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 요청 하나에서 상품 조회 도구가 돌려준 상품을 보관합니다.
 * toolContext로 도구에 전달되며, LLM 호출이 끝난 뒤 AiService가 출력 검사 근거와 화면용 카드로 꺼냅니다.
 */
public class ProductDataRecorder {

    // AiService가 toolContext를 만들 때와 상품 조회 도구가 읽을 때 같은 키를 씁니다.
    public static final String CONTEXT_KEY = "productDataRecorder";
    /** 출력 검사 근거로 덧붙이는 도구 조회 결과 섹션의 제목입니다. */
    public static final String SECTION_TITLE = "상품 정보(도구 조회 결과)";

    // 도구 실행과 결과 확인이 다른 스레드에서 일어나도 기록이 빠지지 않게 합니다.
    private final List<ProductCard> cards = new CopyOnWriteArrayList<>();
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<SearchRecord> searches = new CopyOnWriteArrayList<>();
    private final String question;
    private final String conditionText;
    private final int limit;
    private final boolean refineSearch;

    /**
     * 도구가 조회를 한 번 마친 기록입니다.
     *
     * @param total   조건에 맞는 전체 상품 수
     * @param listing 목록 답변입니다. 목록 요청이 아니었거나 상품 수가 모자라면 null입니다.
     */
    public record SearchRecord(ProductSearch search, long total, ProductListAnswer listing) {
    }

    public ProductDataRecorder() {
        this(null);
    }

    /**
     * 버튼으로 조건을 고른 조회(LLM 없이 도구를 바로 부름)의 보관소입니다. 결과를 LLM에 넣지 않으므로 limit개까지 돌려주고,
     * 상품이 하나여도 목록 답변을 만듭니다. 질문이 없어 조건의 근거 확인·이름 보정은 하지 않습니다.
     */
    public static ProductDataRecorder forRefine(int limit) {
        return new ProductDataRecorder(null, null, limit, true);
    }

    /** question은 사용자 질문 원문입니다. 요금제 도구가 모델이 빠뜨린 시리즈 이름을 찾는 데 씁니다. */
    public ProductDataRecorder(String question) {
        this(question, List.of());
    }

    /**
     * previousQuestions는 이전 대화의 사용자 질문입니다(답변 제외). "그중 5G만"처럼 이어 묻는 질문의 조건이
     * 앞 질문에 있을 수 있어, 도구가 모델이 넣은 조건의 근거를 찾을 때 이번 질문과 함께 봅니다(ConditionGrounding).
     */
    public ProductDataRecorder(String question, List<String> previousQuestions) {
        this(question, question == null ? null
                : String.join("\n", Stream.concat(Stream.of(question), previousQuestions.stream()).toList()),
                ProductCatalogQuery.LIMIT, false);
    }

    private ProductDataRecorder(String question, String conditionText, int limit, boolean refineSearch) {
        this.question = question;
        this.conditionText = conditionText;
        this.limit = limit;
        this.refineSearch = refineSearch;
    }

    /** 도구가 조회할 최대 상품 수입니다. LLM 경로와 버튼 경로 모두 ProductCatalogQuery.LIMIT입니다. */
    public int limit() {
        return limit;
    }

    /** 버튼 조건 조회면 true입니다. 상품이 하나뿐이어도 목록 답변을 만듭니다. */
    public boolean refineSearch() {
        return refineSearch;
    }

    public String question() {
        return question;
    }

    /** 조건의 근거를 찾을 문장입니다. 질문 없이 만든 보관소(평가 등)면 null이고, 도구는 조건을 그대로 씁니다. */
    public String conditionText() {
        return conditionText;
    }

    /** line은 도구가 LLM에 돌려준 그 상품의 행입니다. */
    public void record(ProductCard card, String line) {
        cards.add(card);
        lines.add(line);
    }

    /** 특정 상품의 행은 아니지만 도구가 LLM에 돌려준 줄(여러 상품의 공통 조건)입니다. 카드는 만들지 않습니다. */
    public void note(String line) {
        lines.add(line);
    }

    /** 도구가 조회를 한 번 마쳤습니다. 0건이어도 남깁니다. */
    public void searched(SearchRecord record) {
        searches.add(record);
    }

    /** 조회가 한 번뿐이었을 때 그 기록입니다. 여러 번 조회했으면 질문이 여러 상품을 묻는 것이라 비어 있습니다. */
    public Optional<SearchRecord> search() {
        return searches.size() == 1 ? Optional.of(searches.getFirst()) : Optional.empty();
    }

    /**
     * 조회가 한 번뿐이었고 그 조회가 목록 요청이었을 때의 목록입니다.
     * 여러 번 조회했으면 질문이 여러 상품을 묻는 것이라 목록 답변 하나로 대신하지 않습니다.
     */
    public Optional<ProductListAnswer> listing() {
        return search().map(SearchRecord::listing);
    }

    /**
     * 버튼으로 조건을 골라 다시 조회하게 할 선택지입니다. 조회가 한 번뿐이고, 결과가 limit개를 넘거나 0건일 때만 있습니다.
     * 결과가 다 보이면 더 좁힐 이유가 없기 때문입니다.
     */
    public Optional<ProductRefine> refine() {
        return search().filter(record -> record.total() == 0 || record.total() > limit)
                .map(record -> ProductRefine.of(record.search(), record.total()));
    }

    public List<ProductCard> cards() {
        return List.copyOf(cards);
    }

    /** 도구가 LLM에 돌려준 상품 행을 같은 표기로 모읍니다. 조회한 상품이 없으면 빈 문자열입니다. */
    public String describe() {
        return lines.stream().distinct().collect(Collectors.joining("\n"));
    }
}
