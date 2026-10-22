package com.ubot.ai.tool;

import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.billing.enums.PaymentMethod;
import com.ubot.billing.enums.PaymentStatus;
import com.ubot.common.PageResponseDto;
import com.ubot.contract.dto.response.InstallmentResponseDto;
import com.ubot.contract.dto.response.SubscriptionContractResponseDto;
import com.ubot.contract.enums.ContractStatus;
import com.ubot.contract.enums.ContractType;
import com.ubot.contract.enums.InstallmentStatus;
import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.plan.dto.response.PlanSummaryResponseDto;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.enums.SimStatus;
import com.ubot.subscription.enums.SimType;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.usage.dto.response.UsageAllowanceResponseDto;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/** 사용자 가입 정보 요약기·도구 테스트가 같이 쓰는 준비물입니다. */
final class UserDataFixtures {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 2026-10-12 00:30 KST. UTC로는 아직 10월 11일이라 시간대를 잘못 쓰면 기준일이 달라집니다.
    static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 10, 12, 0, 30).atZone(SEOUL).toInstant(), SEOUL);
    static final String SIM_IDENTIFIER = "********1234";

    private UserDataFixtures() {
    }

    static SubscriptionResponseDto subscription(Long id, String phoneNumber, SubscriptionStatus status) {
        return new SubscriptionResponseDto(id, phoneNumber, status, LocalDateTime.of(2024, 3, 2, 10, 0), null,
                1L, "5G 프리미어", SimType.USIM, SIM_IDENTIFIER, SimStatus.ACTIVE,
                LocalDateTime.of(2024, 3, 2, 10, 0), null);
    }

    static PlanDetailResponseDto plan() {
        return new PlanDetailResponseDto(new PlanSummaryResponseDto(1L, "P001", "5G 스탠다드", null, null,
                75000, 153600L, false, 5000, null, true, null, true), "설명", null, null, null);
    }

    static UsageRecordResponseDto usage(YearMonth month) {
        return new UsageRecordResponseDto(1L, month, 12595L, 120, 3,
                new UsageAllowanceResponseDto(153600L, false, null, true, null, true),
                141005L, null, null, month.atDay(11), false, null, null);
    }

    static BillingRecordResponseDto billing(YearMonth month, PaymentStatus status) {
        boolean paid = status == PaymentStatus.PAID;
        return new BillingRecordResponseDto(1L, month, 75000, 3000, 0, 0, 0, 78000,
                month.atDay(25), status, paid ? 78000 : 0, paid ? PaymentMethod.AUTO_TRANSFER : null,
                paid ? month.atDay(20).atStartOfDay() : null);
    }

    static SubscriptionContractResponseDto contract() {
        return new SubscriptionContractResponseDto(1L, ContractType.SELECTIVE_CONTRACT,
                LocalDate.of(2024, 3, 2), LocalDate.of(2026, 3, 1), 18750, ContractStatus.ACTIVE, null,
                new InstallmentResponseDto("iPhone 15", 1200000, 24, 19, 50000, 250000,
                        LocalDate.of(2024, 3, 2), LocalDate.of(2026, 3, 1), InstallmentStatus.ACTIVE));
    }

    static <T> PageResponseDto<T> page(List<T> content) {
        return PageResponseDto.of(content, 0, Math.max(1, content.size()), content.size());
    }
}
