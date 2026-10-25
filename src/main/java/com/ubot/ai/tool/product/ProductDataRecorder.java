package com.ubot.ai.tool.product;

import com.ubot.ai.dto.ProductDataCards.ProductCard;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

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
    private final List<Optional<ProductListAnswer>> searches = new CopyOnWriteArrayList<>();
    private final String question;

    public ProductDataRecorder() {
        this(null);
    }

    /** question은 사용자 질문 원문입니다. 요금제 도구가 모델이 빠뜨린 시리즈 이름을 찾는 데 씁니다. */
    public ProductDataRecorder(String question) {
        this.question = question;
    }

    public String question() {
        return question;
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

    /** 도구가 조회를 한 번 마쳤습니다. 목록 요청이 아니었거나 상품이 2개 미만이면 listing은 null입니다. */
    public void searched(ProductListAnswer listing) {
        searches.add(Optional.ofNullable(listing));
    }

    /**
     * 조회가 한 번뿐이었고 그 조회가 목록 요청이었을 때의 목록입니다.
     * 여러 번 조회했으면 질문이 여러 상품을 묻는 것이라 목록 답변 하나로 대신하지 않습니다.
     */
    public Optional<ProductListAnswer> listing() {
        return searches.size() == 1 ? searches.getFirst() : Optional.empty();
    }

    public List<ProductCard> cards() {
        return List.copyOf(cards);
    }

    /** 도구가 LLM에 돌려준 상품 행을 같은 표기로 모읍니다. 조회한 상품이 없으면 빈 문자열입니다. */
    public String describe() {
        return lines.stream().distinct().collect(Collectors.joining("\n"));
    }
}
