package com.ubot.ai.dto;

import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.contract.dto.response.SubscriptionContractResponseDto;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.subscription.dto.response.SubscriptionAddonResponseDto;
import com.ubot.subscription.dto.response.SubscriptionBundleResponseDto;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.dto.response.SubscriptionRoamingResponseDto;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import java.time.LocalDate;
import java.util.List;

/**
 * USER_DATA 질문에서 도구 호출 없이 답할 수 있도록 LLM에 미리 넣는 사용자 정보입니다.
 * 지난 달 사용량·청구서만 빼고 담으며, 그 기록은 UserDataTools가 조회합니다.
 * subscriptions는 이용 중인(해지되지 않은) 회선을 최근 개통 순으로 모두 담습니다.
 */
public record UserDataSummary(
        LocalDate baseDate,
        Profile profile,
        List<SubscriptionSummary> subscriptions) {

    public UserDataSummary {
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
    }

    /** 이메일과 생년월일 원본은 답변에 필요하지 않아 담지 않습니다. age는 기준일의 만 나이이고, 생년월일이 없으면 null입니다. */
    public record Profile(String name, Integer age, String residenceArea, LocalDate joinedAt) {
    }

    /**
     * 회선 하나의 가입 정보입니다. 기록이 없으면 latestUsage, latestBilling, activeContract는 null입니다.
     * addons·bundles는 이용 중인 것만, roamings는 이용 중이거나 예약된 것만 담습니다.
     */
    public record SubscriptionSummary(
            SubscriptionResponseDto subscription,
            PlanDetailResponseDto plan,
            UsageRecordResponseDto latestUsage,
            BillingRecordResponseDto latestBilling,
            List<BillingRecordResponseDto> unpaidBillings,
            SubscriptionContractResponseDto activeContract,
            List<SubscriptionAddonResponseDto> addons,
            List<SubscriptionBundleResponseDto> bundles,
            List<SubscriptionRoamingResponseDto> roamings) {

        public SubscriptionSummary {
            unpaidBillings = unpaidBillings == null ? List.of() : List.copyOf(unpaidBillings);
            addons = addons == null ? List.of() : List.copyOf(addons);
            bundles = bundles == null ? List.of() : List.copyOf(bundles);
            roamings = roamings == null ? List.of() : List.copyOf(roamings);
        }
    }
}
