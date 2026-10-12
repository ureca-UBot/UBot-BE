package com.ubot.ai.dto;

import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.contract.dto.response.SubscriptionContractResponseDto;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import java.time.LocalDate;
import java.util.List;

/**
 * USER_DATA 질문에서 도구 호출 없이 답할 수 있도록 LLM에 미리 넣는 사용자 정보입니다.
 * subscriptions는 이용 중인 회선 중 앞에서부터 일부만 담고, 전체 개수는 inUseSubscriptionCount에 둡니다.
 */
public record UserDataSummary(
        LocalDate baseDate,
        Profile profile,
        List<SubscriptionSummary> subscriptions,
        int inUseSubscriptionCount) {

    public UserDataSummary {
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
    }

    /** 이메일과 생년월일 원본은 답변에 필요하지 않아 담지 않습니다. age는 기준일의 만 나이이고, 생년월일이 없으면 null입니다. */
    public record Profile(String name, Integer age, String residenceArea, LocalDate joinedAt) {
    }

    /** 회선 하나의 자주 묻는 정보입니다. 기록이 없으면 latestUsage, latestBilling, activeContract는 null입니다. */
    public record SubscriptionSummary(
            SubscriptionResponseDto subscription,
            PlanDetailResponseDto plan,
            UsageRecordResponseDto latestUsage,
            BillingRecordResponseDto latestBilling,
            List<BillingRecordResponseDto> unpaidBillings,
            SubscriptionContractResponseDto activeContract) {

        public SubscriptionSummary {
            unpaidBillings = unpaidBillings == null ? List.of() : List.copyOf(unpaidBillings);
        }
    }
}
