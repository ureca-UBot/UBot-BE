package com.ubot.ai.dto;

import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 답변에 쓰인 요금제·사용량·청구서를 화면이 표로 그릴 수 있게 DB 값 그대로 담습니다.
 * LLM 문장과 별도로 응답에 실리며, 목록은 비어 있을 수는 있어도 null이 아닙니다. phoneNumber는 마스킹된 번호입니다.
 */
public record UserDataCards(List<PlanCard> plans, List<UsageCard> usages, List<BillingCard> billings) {

    public static final UserDataCards EMPTY = new UserDataCards(List.of(), List.of(), List.of());

    public UserDataCards {
        plans = plans == null ? List.of() : List.copyOf(plans);
        usages = usages == null ? List.of() : List.copyOf(usages);
        billings = billings == null ? List.of() : List.copyOf(billings);
    }

    public record PlanCard(String phoneNumber, PlanDetailResponseDto plan) {
    }

    /** usage.billingMonth는 사용한 달입니다. */
    public record UsageCard(String phoneNumber, UsageRecordResponseDto usage) {
    }

    /** billing.billingMonth는 청구서가 나온 달이고, usageMonth는 그 청구서가 청구하는 사용 월(전월)입니다. */
    public record BillingCard(String phoneNumber, int usageMonth, BillingRecordResponseDto billing) {

        public static BillingCard of(String phoneNumber, BillingRecordResponseDto billing) {
            return new BillingCard(phoneNumber, billing.billingMonth().minusMonths(1).getMonthValue(), billing);
        }
    }

    /**
     * 앞의 카드부터 차례로 합치고 같은 회선의 같은 기록(요금제는 회선, 사용량·청구서는 회선과 달)은 하나만 남깁니다.
     * 카드가 하나도 없으면 응답에 싣지 않도록 null을 돌려줍니다.
     */
    public static UserDataCards merge(UserDataCards first, UserDataCards second) {
        List<UserDataCards> all = List.of(orEmpty(first), orEmpty(second));
        List<PlanCard> plans = distinct(all, UserDataCards::plans, PlanCard::phoneNumber);
        List<UsageCard> usages = distinct(all, UserDataCards::usages,
                card -> card.phoneNumber() + "|" + card.usage().billingMonth());
        List<BillingCard> billings = distinct(all, UserDataCards::billings,
                card -> card.phoneNumber() + "|" + card.billing().billingMonth());
        if (plans.isEmpty() && usages.isEmpty() && billings.isEmpty()) {
            return null;
        }
        return new UserDataCards(plans, usages, billings);
    }

    private static UserDataCards orEmpty(UserDataCards cards) {
        return cards == null ? EMPTY : cards;
    }

    private static <T> List<T> distinct(List<UserDataCards> all, Function<UserDataCards, List<T>> items,
            Function<T, String> key) {
        Map<String, T> unique = new LinkedHashMap<>();
        all.forEach(cards -> items.apply(cards).forEach(item -> unique.putIfAbsent(key.apply(item), item)));
        return List.copyOf(unique.values());
    }
}
