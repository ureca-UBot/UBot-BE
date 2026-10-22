package com.ubot.ai.tool;

import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.dto.UserDataCards.BillingCard;
import com.ubot.ai.dto.UserDataCards.PlanCard;
import com.ubot.ai.dto.UserDataCards.UsageCard;
import com.ubot.ai.dto.UserDataSummary;
import com.ubot.ai.dto.UserDataSummary.SubscriptionSummary;
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 미리 넣은 가입 정보와 도구로 조회한 기록 중 답변에 실제로 쓰인 기록을 찾아 화면용 카드로 만듭니다.
 * 질문이 아니라 답변을 기준으로 하므로 "결합 상품은 없습니다"처럼 기록을 쓰지 않은 답에는 카드를 붙이지 않습니다.
 * 도구로 조회한 기록도 조회만 하고 답변에 쓰지 않았을 수 있어 같은 기준으로 거릅니다.
 * 기록마다 답변에 나올 고유 문자열(요금제 이름, "10월 청구서"와 총액, "18.5GB" 등)을 요약과 같은 형식으로 만들어 찾습니다.
 */
public final class UserDataCardSelector {

    // 요금제는 "네, 무제한입니다"처럼 이름 없이 답하는 경우가 많아, 질문에 "요금제"가 있으면 답변과 상관없이 붙입니다.
    private static final String PLAN_KEYWORD = "요금제";
    private static final Pattern PHONE_LAST4 = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)");
    private static final Pattern THIS_MONTH = Pattern.compile("이번\\s*달|이달");

    private UserDataCardSelector() {
    }

    public static UserDataCards select(String question, String answer, UserDataSummary summary) {
        if (summary == null || !StringUtils.hasText(answer)) {
            return UserDataCards.EMPTY;
        }
        List<SubscriptionSummary> askedSubscriptions = askedSubscriptions(question, summary.subscriptions());
        boolean askedPlan = question != null && question.contains(PLAN_KEYWORD);

        List<PlanCard> plans = new ArrayList<>();
        List<UsageCard> usages = new ArrayList<>();
        List<BillingCard> billings = new ArrayList<>();
        for (SubscriptionSummary subscription : summary.subscriptions()) {
            // 질문이 특정 회선을 가리키면 그 회선의 카드만 붙입니다. 회선마다 청구서가 같은 달에 나오고
            // 요금제가 같을 수도 있어, 답변 문자열만으로는 어느 회선의 기록인지 가릴 수 없습니다.
            if (!askedSubscriptions.contains(subscription)) {
                continue;
            }
            String phoneNumber = UserDataSummarizer.maskPhone(subscription.subscription().phoneNumber());
            if (subscription.plan() != null && (mentions(answer, subscription.plan().summary().name()) || askedPlan)) {
                plans.add(new PlanCard(phoneNumber, subscription.plan()));
            }
            UsageRecordResponseDto usage = subscription.latestUsage();
            if (usage != null && mentionsUsage(answer, usage)) {
                usages.add(new UsageCard(phoneNumber, usage));
            }
            for (BillingRecordResponseDto billing : billingsOf(subscription)) {
                boolean latest = billing == subscription.latestBilling();
                if (latest ? mentionsLatestBilling(answer, billing) : mentionsBilling(answer, billing)) {
                    billings.add(BillingCard.of(phoneNumber, billing));
                }
            }
        }
        return new UserDataCards(plans, usages, billings);
    }

    /**
     * 도구로 조회한 기록 중 답변에 쓰인 것만 남깁니다. 요약이 실패해도 도구는 쓰이므로 요약 없이 거릅니다.
     * 질문이 특정 회선을 가리키면 요약과 마찬가지로 그 회선의 기록만 남깁니다.
     */
    public static UserDataCards selectRecorded(String question, String answer, UserDataCards recorded) {
        if (recorded == null || !StringUtils.hasText(answer)) {
            return UserDataCards.EMPTY;
        }
        List<UsageCard> usages = askedCards(question, recorded.usages(), UsageCard::phoneNumber).stream()
                .filter(card -> mentionsUsage(answer, card.usage()))
                .toList();
        List<BillingCard> billings = askedCards(question, recorded.billings(), BillingCard::phoneNumber).stream()
                .filter(card -> mentionsBilling(answer, card.billing()))
                .toList();
        return new UserDataCards(List.of(), usages, billings);
    }

    /** 질문에 끝 4자리가 있고 그 번호의 회선이 있으면 그 회선만, 아니면 모든 회선입니다. */
    private static List<SubscriptionSummary> askedSubscriptions(String question, List<SubscriptionSummary> subscriptions) {
        return askedCards(question, subscriptions, subscription -> subscription.subscription().phoneNumber());
    }

    /** askedSubscriptions와 같은 규칙을 회선 정보 대신 전화번호(마스킹 포함)만 있는 카드에도 씁니다. */
    private static <T> List<T> askedCards(String question, List<T> items, Function<T, String> phoneNumber) {
        if (question == null) {
            return items;
        }
        Matcher matcher = PHONE_LAST4.matcher(question);
        while (matcher.find()) {
            String last4 = matcher.group(1);
            List<T> matched = items.stream()
                    .filter(item -> digits(phoneNumber.apply(item)).endsWith(last4))
                    .toList();
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        return items;
    }

    /** 요약에 들어간 최신 청구서와 미납 청구서입니다. 같은 달은 한 번만 봅니다. */
    private static List<BillingRecordResponseDto> billingsOf(SubscriptionSummary subscription) {
        Map<Object, BillingRecordResponseDto> byMonth = new LinkedHashMap<>();
        if (subscription.latestBilling() != null) {
            byMonth.put(subscription.latestBilling().billingMonth(), subscription.latestBilling());
        }
        subscription.unpaidBillings().forEach(billing -> byMonth.putIfAbsent(billing.billingMonth(), billing));
        return List.copyOf(byMonth.values());
    }

    /**
     * 모델은 요약의 "2026년 10월 청구서"를 그대로 옮기거나 연도를 빼고 "10월 청구서"로 씁니다.
     * 월만 보면 "10월 청구서는 확인할 수 없습니다"처럼 그 청구서를 쓰지 않은 답에도 걸리므로, 총액까지 나와야 쓴 것으로 봅니다.
     */
    private static boolean mentionsBilling(String answer, BillingRecordResponseDto billing) {
        return billing.totalAmount() != null
                && mentions(answer, billing.billingMonth().getMonthValue() + "월 청구서")
                && mentions(answer, UserDataSummarizer.won(billing.totalAmount()));
    }

    /**
     * 요약의 최근 청구서는 "이번 달 요금"으로 소개하므로("최근 청구서(이번 달 요금·…)"), 모델이 월 대신 "이번 달"로 써도 그 청구서로 봅니다.
     * 도구로 조회한 기록은 대부분 지난 달이라, 총액이 같은 달이 있으면 "이번 달"로는 어느 달인지 가릴 수 없어 여기서만 씁니다.
     */
    private static boolean mentionsLatestBilling(String answer, BillingRecordResponseDto billing) {
        return mentionsBilling(answer, billing)
                || billing.totalAmount() != null && THIS_MONTH.matcher(answer).find()
                        && mentions(answer, UserDataSummarizer.won(billing.totalAmount()));
    }

    /**
     * 문자 건수는 다른 숫자와 겹치기 쉬워 데이터 사용량·잔여량과 음성 사용 시간만 봅니다.
     * 데이터를 쓰지 않았으면 잔여량이 요금제 제공량과 같아 요금제 안내("데이터 150.0GB")에도 걸리므로, 그때는 사용량 값으로만 봅니다.
     */
    private static boolean mentionsUsage(String answer, UsageRecordResponseDto usage) {
        return usage.dataUsedMb() != null && mentions(answer, UserDataSummarizer.megabytes(usage.dataUsedMb()))
                || remainingDiffersFromAllowance(usage) && mentions(answer, UserDataSummarizer.megabytes(usage.remainingDataMb()))
                || usage.voiceUsedMinutes() != null && mentions(answer, usage.voiceUsedMinutes() + "분");
    }

    /** 제공량을 모르면 사용량이 0이 아닐 때만 잔여량을 제공량과 다른 값으로 봅니다. */
    private static boolean remainingDiffersFromAllowance(UsageRecordResponseDto usage) {
        if (usage.remainingDataMb() == null) {
            return false;
        }
        Long allowance = usage.allowance() == null ? null : usage.allowance().dataAmountMb();
        return allowance != null
                ? !allowance.equals(usage.remainingDataMb())
                : usage.dataUsedMb() != null && usage.dataUsedMb() != 0;
    }

    /** "1월 청구서"가 "11월 청구서"에, "8.5GB"가 "18.5GB"에 걸리지 않도록 앞이 숫자·점이면 다른 값으로 봅니다. */
    private static boolean mentions(String answer, String token) {
        return StringUtils.hasText(token)
                && Pattern.compile("(?<![\\d.])" + Pattern.quote(token)).matcher(answer).find();
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }
}
