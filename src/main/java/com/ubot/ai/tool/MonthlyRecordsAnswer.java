package com.ubot.ai.tool;

import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.dto.UserDataCards.BillingCard;
import com.ubot.ai.dto.UserDataCards.UsageCard;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * "8월 9월 요금 알려줘"처럼 여러 달을 물었을 때 LLM 답변 대신 쓰는 답변입니다. 가입 정보 도구가 조회한 기록을 달마다 한 줄씩 씁니다.
 * 작은 모델은 도구가 여러 달을 돌려줘도 첫 달만 답하는 일이 있어, 상품 목록 답변(ProductListing)처럼 형식과 달 수를 코드가 정합니다.
 * "비교", "차이"처럼 달 사이 계산이 필요한 질문과, 청구서와 사용량을 함께 조회한 질문은 LLM 답변을 씁니다.
 *
 * @param answer 답변 본문
 * @param cards  화면 카드. 답변에 쓴 기록 전부입니다.
 */
public record MonthlyRecordsAnswer(String answer, UserDataCards cards) {

    static final String BILLING_HEADER = "요청하신 달의 청구서입니다.";
    static final String USAGE_HEADER = "요청하신 달의 사용량입니다.";

    /** 질문이 여러 달을 묻고 도구가 같은 종류의 기록을 두 달 이상 조회했을 때만 있습니다. */
    public static Optional<MonthlyRecordsAnswer> of(String question, UserDataCards recorded) {
        if (recorded == null || !QuestionMonths.asksMultipleMonths(question) || QuestionMonths.asksComparison(question)) {
            return Optional.empty();
        }
        // 모델이 조회한 달을 도구가 질문 속 달로 다시 조회하면 같은 기록이 두 번 남으므로 한 번씩만 씁니다.
        List<BillingCard> billings = recorded.billings().stream().distinct().toList();
        List<UsageCard> usages = recorded.usages().stream().distinct().toList();
        if (billings.size() >= 2 && usages.isEmpty()) {
            List<BillingCard> sorted = billings.stream()
                    .sorted(Comparator.comparing((BillingCard card) -> card.billing().billingMonth())).toList();
            String text = lines(BILLING_HEADER, sorted, BillingCard::phoneNumber,
                    card -> UserDataSummarizer.formatBilling(card.billing()));
            return Optional.of(new MonthlyRecordsAnswer(text, new UserDataCards(List.of(), List.of(), sorted)));
        }
        if (usages.size() >= 2 && billings.isEmpty()) {
            List<UsageCard> sorted = usages.stream()
                    .sorted(Comparator.comparing((UsageCard card) -> card.usage().billingMonth())).toList();
            String text = lines(USAGE_HEADER, sorted, UsageCard::phoneNumber,
                    card -> UserDataSummarizer.formatUsage(card.usage()));
            return Optional.of(new MonthlyRecordsAnswer(text, new UserDataCards(List.of(), sorted, List.of())));
        }
        return Optional.empty();
    }

    /** 회선이 둘 이상이면 줄 앞에 어느 회선인지 붙입니다. */
    private static <T> String lines(String header, List<T> records, Function<T, String> phoneOf,
            Function<T, String> format) {
        boolean severalLines = records.stream().map(phoneOf).distinct().count() > 1;
        StringBuilder text = new StringBuilder(header);
        for (T record : records) {
            text.append("\n- ");
            if (severalLines) {
                text.append(phoneOf.apply(record)).append(" 회선 ");
            }
            text.append(format.apply(record));
        }
        return text.toString();
    }
}
