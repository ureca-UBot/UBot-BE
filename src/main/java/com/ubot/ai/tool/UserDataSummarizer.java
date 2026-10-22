package com.ubot.ai.tool;

import com.ubot.ai.dto.UserDataSummary;
import com.ubot.ai.dto.UserDataSummary.Profile;
import com.ubot.ai.dto.UserDataSummary.SubscriptionSummary;
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.billing.enums.PaymentMethod;
import com.ubot.billing.enums.PaymentStatus;
import com.ubot.billing.service.BillingService;
import com.ubot.contract.dto.response.InstallmentResponseDto;
import com.ubot.contract.dto.response.SubscriptionContractResponseDto;
import com.ubot.contract.enums.ContractType;
import com.ubot.contract.enums.InstallmentStatus;
import com.ubot.contract.service.ContractService;
import com.ubot.plan.dto.response.PlanSummaryResponseDto;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.dto.response.SubscriptionRoamingResponseDto;
import com.ubot.subscription.enums.SubscriptionAddonStatus;
import com.ubot.subscription.enums.SubscriptionBundleStatus;
import com.ubot.subscription.enums.SubscriptionRoamingStatus;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.usage.dto.response.UsageAllowanceResponseDto;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import com.ubot.usage.service.UsageService;
import com.ubot.user.dto.response.UserResponseDto;
import com.ubot.user.service.UserService;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * USER_DATA 질문에서 도구 호출을 줄이려고 LLM에 미리 넣는 사용자 정보를 조회하고 문자열로 정리합니다.
 * 프로필, 요금제, 최근 사용량, 최근 청구·미납, 약정·할부, 부가서비스·결합·로밍을 담고,
 * 그보다 이전 달의 사용량·청구서만 UserDataTools가 조회합니다.
 * 부가서비스·결합·로밍을 도구로 두면 작은 모델이 도구를 부르지 않고 청구서 항목이나 요금제 이름으로 목록을 지어내서 미리 넣습니다.
 * 회선·사용량·청구 한 건을 문자열로 만드는 형식은 UserDataTools와 같이 씁니다.
 */
@Component
@RequiredArgsConstructor
public class UserDataSummarizer {

    // 청구서는 한 달에 한 건이므로 최근 6개월 안의 미납을 찾습니다.
    static final int RECENT_BILLING_COUNT = 6;

    static final String NO_SUBSCRIPTION_MESSAGE = "이용 중인 회선이 없습니다.";
    // "이번 달 요금"을 어떻게 답할지 지시문으로 적으면 모델이 도구가 필요한 질문에서도 바로 답하려 합니다.
    // 지시문 대신 청구서 줄 자체에 사용자가 묻는 표현을 붙여, 이번 달 요금 질문은 이 줄로 답하게 합니다.
    static final String LATEST_BILLING_LABEL = "최근 청구서(이번 달 요금·청구 금액·납부 상태)";
    // "도구로 조회하지 마세요" 같은 금지형 문장은 작은 모델이 도구 전체를 쓰지 말라는 뜻으로 받아들여 도구를 호출하지 않습니다.
    // 그래서 이 자료에 무엇이 있는지 알려 주고, 없는 정보는 도구로 조회하라고 긍정형으로만 안내합니다.
    // 가장 최근 달을 다시 조회하지 않게 하는 것은 UserDataTools가 코드로 막습니다.
    static final String TOOL_GUIDE =
            "그보다 이전 달의 사용량·청구서를 물으면 답하기 전에 반드시 도구를 호출해 조회하세요.";
    // DB에 없는 값을 LLM이 지어내지 않고 확인할 수 없다고 답하게 합니다.
    static final String UNAVAILABLE_NOTICE =
            "제공되지 않는 정보: 소액결제, 포인트·멤버십, 할인반환금(위약금), 요금제 변경 가능 여부, "
                    + "로밍 차단·번호도용 방지 서비스 가입 여부, 중고폰 반납 보상. 이 정보는 추측하지 말고 확인할 수 없다고 안내하세요.";
    // "9월 요금"은 9월 사용분으로도 9월 청구서로도 읽히므로, 어느 청구서로 답했는지 밝혀 고객이 정정할 수 있게 합니다.
    // "요금·청구서 답변은 ~" 같은 질문 유형 지시는 이번 달 요금 질문에서도 최근 청구서를 도구로 다시 조회하게 만들므로,
    // 청구서 줄에 "기준"을 적고 그 줄을 쓸 때만 기준을 밝히도록 줄 끝에 붙입니다.
    static final String SUMMARY_BILLING_SEPARATOR = " 기준: 총 ";
    static final String BILLING_BASIS_NOTE = "이 청구서를 근거로 답할 때는 이 기준을 밝힌다.";

    private static final String UNKNOWN = "정보 없음";

    private final UserService userService;
    private final SubscriptionService subscriptionService;
    private final UsageService usageService;
    private final BillingService billingService;
    private final ContractService contractService;
    private final Clock clock;

    /** 사용자가 없으면 UserException을 그대로 던집니다. 이용 중인 회선을 최근 개통 순으로 모두 조회합니다. */
    public UserDataSummary summarize(Long userId) {
        LocalDate baseDate = LocalDate.now(clock);
        Profile profile = toProfile(userService.getActiveUser(userId), baseDate);

        // 요약에서 빠진 회선은 도구로도 요금제·약정을 조회할 수 없으므로 이용 중인 회선은 모두 담습니다.
        List<SubscriptionSummary> subscriptions = getInUseSubscriptionList(subscriptionService, userId).stream()
                .map(subscription -> summarizeSubscription(userId, subscription))
                .toList();
        return new UserDataSummary(baseDate, profile, subscriptions);
    }

    /** 조회 결과를 LLM이 읽을 문자열로 만듭니다. */
    public String format(UserDataSummary summary) {
        StringBuilder text = new StringBuilder();
        text.append("조회 기준일: ").append(summary.baseDate()).append('\n');
        text.append(formatProfile(summary.profile())).append('\n');

        if (summary.subscriptions().isEmpty()) {
            text.append(NO_SUBSCRIPTION_MESSAGE).append('\n');
        } else {
            text.append("이용 중인 회선: ").append(summary.subscriptions().size()).append("개\n");
            for (int i = 0; i < summary.subscriptions().size(); i++) {
                text.append(formatSubscriptionSummary(i + 1, summary.subscriptions().get(i)));
            }
        }
        text.append('\n').append(coverageNotice(summary)).append(TOOL_GUIDE).append('\n');
        return text.append(UNAVAILABLE_NOTICE).toString();
    }

    /** 이 자료에 들어 있는 사용량·청구서의 달을 알려 줍니다. 예: "이 자료에는 가장 최근 달(사용량 2026-10, 2026년 10월 청구서)만 있습니다. " */
    private String coverageNotice(UserDataSummary summary) {
        String usageMonths = summary.subscriptions().stream()
                .filter(subscription -> subscription.latestUsage() != null)
                .map(subscription -> subscription.latestUsage().billingMonth().toString())
                .distinct().sorted(Comparator.reverseOrder()).collect(Collectors.joining("·"));
        String billingMonths = summary.subscriptions().stream()
                .filter(subscription -> subscription.latestBilling() != null)
                .map(subscription -> subscription.latestBilling().billingMonth())
                .distinct().sorted(Comparator.reverseOrder())
                .map(UserDataSummarizer::monthLabel).collect(Collectors.joining("·"));
        List<String> parts = new ArrayList<>();
        if (!usageMonths.isEmpty()) {
            parts.add("사용량 " + usageMonths);
        }
        if (!billingMonths.isEmpty()) {
            parts.add(billingMonths + " 청구서");
        }
        if (parts.isEmpty()) {
            return "이 자료에는 사용량·청구서 기록이 없습니다. ";
        }
        return "이 자료에는 가장 최근 달(" + String.join(", ", parts) + ")만 있습니다. ";
    }

    /** 해지되지 않은(이용 중·일시정지) 회선을 최근 개통 순으로 돌려줍니다. */
    static List<SubscriptionResponseDto> getInUseSubscriptionList(SubscriptionService subscriptionService, Long userId) {
        return subscriptionService.getMySubscriptionList(userId).stream()
                .filter(subscription -> subscription.status() != SubscriptionStatus.TERMINATED)
                .toList();
    }

    private SubscriptionSummary summarizeSubscription(Long userId, SubscriptionResponseDto subscription) {
        Long subscriptionId = subscription.subscriptionId();
        // 사용량·청구는 최신 월부터 정렬되어 오므로 첫 건이 가장 최근 기록입니다.
        UsageRecordResponseDto latestUsage = usageService
                .getMyUsageRecordList(userId, subscriptionId, null, 0, 1).content().stream()
                .findFirst().orElse(null);
        List<BillingRecordResponseDto> recentBillings = billingService
                .getMyBillingRecordList(userId, subscriptionId, null, 0, RECENT_BILLING_COUNT).content();
        List<BillingRecordResponseDto> unpaidBillings = recentBillings.stream()
                .filter(billing -> billing.paymentStatus() != PaymentStatus.PAID)
                .toList();
        return new SubscriptionSummary(
                subscription,
                subscriptionService.getMyPlan(userId, subscriptionId),
                latestUsage,
                recentBillings.isEmpty() ? null : recentBillings.getFirst(),
                unpaidBillings,
                contractService.getMyContract(userId, subscriptionId),
                subscriptionService.getMyAddonList(userId, subscriptionId).stream()
                        .filter(addon -> addon.status() == SubscriptionAddonStatus.ACTIVE)
                        .toList(),
                subscriptionService.getMyBundleList(userId, subscriptionId).stream()
                        .filter(bundle -> bundle.status() == SubscriptionBundleStatus.ACTIVE)
                        .toList(),
                subscriptionService.getMyRoamingList(userId, subscriptionId).stream()
                        .filter(roaming -> roaming.status() == SubscriptionRoamingStatus.ACTIVE
                                || roaming.status() == SubscriptionRoamingStatus.SCHEDULED)
                        .toList());
    }

    private Profile toProfile(UserResponseDto user, LocalDate baseDate) {
        Integer age = user.birthDate() == null ? null : Period.between(user.birthDate(), baseDate).getYears();
        LocalDate joinedAt = user.createdAt() == null ? null : user.createdAt().toLocalDate();
        return new Profile(user.name(), age, user.residenceArea(), joinedAt);
    }

    private String formatProfile(Profile profile) {
        return "이름: " + valueOrUnknown(profile.name())
                + " | 나이: " + (profile.age() == null ? UNKNOWN : "만 " + profile.age() + "세")
                + " | 거주 지역: " + valueOrUnknown(profile.residenceArea())
                + " | 회원 가입일: " + valueOrUnknown(profile.joinedAt());
    }

    private String formatSubscriptionSummary(int index, SubscriptionSummary summary) {
        StringBuilder text = new StringBuilder();
        text.append("[회선 ").append(index).append("] ").append(formatSubscription(summary.subscription())).append('\n');
        text.append("- 요금제: ").append(formatPlan(summary.plan().summary())).append('\n');
        text.append("- 최근 사용량: ")
                .append(summary.latestUsage() == null ? "기록 없음" : formatUsage(summary.latestUsage())).append('\n');
        text.append("- ").append(LATEST_BILLING_LABEL).append(": ")
                .append(summary.latestBilling() == null ? "기록 없음"
                        : formatBilling(summary.latestBilling(), SUMMARY_BILLING_SEPARATOR) + ". " + BILLING_BASIS_NOTE)
                .append('\n');
        text.append("- 미납: ");
        if (summary.unpaidBillings().isEmpty()) {
            text.append("없음(최근 ").append(RECENT_BILLING_COUNT).append("개월 청구 기준)\n");
        } else {
            List<String> unpaid = new ArrayList<>();
            for (BillingRecordResponseDto billing : summary.unpaidBillings()) {
                unpaid.add(monthLabel(billing.billingMonth()) + " 청구서 미납액 " + won(unpaidAmount(billing))
                        + "(납부 기한 " + valueOrUnknown(billing.dueDate()) + ", " + paymentStatusLabel(billing.paymentStatus()) + ")");
            }
            text.append(String.join(", ", unpaid)).append('\n');
        }
        text.append("- 약정·할부: ")
                .append(summary.activeContract() == null ? "이용 중인 약정 없음" : formatContract(summary.activeContract()))
                .append('\n');
        // 목록이 없으면 청구서의 "부가서비스 요금"이나 요금제 이름을 보고 목록을 지어내므로, 비어 있어도 "없음"으로 적습니다.
        text.append("- 부가서비스(이용 중): ")
                .append(joinOrNone(summary.addons(), addon -> valueOrUnknown(addon.name())
                        + " 월 " + won(addon.monthlyFee())
                        + "(" + valueOrUnknown(toDate(addon.subscribedAt())) + " 가입)"))
                .append('\n');
        text.append("- 결합 상품(이용 중): ")
                .append(joinOrNone(summary.bundles(), bundle -> valueOrUnknown(bundle.name())
                        + " 할인 " + won(bundle.appliedDiscountAmount())
                        + "(" + valueOrUnknown(toDate(bundle.joinedAt())) + " 가입)"))
                .append('\n');
        text.append("- 로밍(이용 중·예약): ")
                .append(joinOrNone(summary.roamings(), UserDataSummarizer::formatRoaming))
                .append('\n');
        return text.toString();
    }

    private static <T> String joinOrNone(List<T> items, Function<T, String> formatter) {
        return items.isEmpty() ? "없음" : items.stream().map(formatter).collect(Collectors.joining(", "));
    }

    private static String formatRoaming(SubscriptionRoamingResponseDto roaming) {
        return valueOrUnknown(roaming.name())
                + " | " + (roaming.status() == SubscriptionRoamingStatus.ACTIVE ? "이용 중" : "이용 예정")
                + " | " + valueOrUnknown(roaming.country())
                + " | " + valueOrUnknown(roaming.startDate()) + "~" + valueOrUnknown(roaming.endDate())
                + " | 일 " + won(roaming.dailyFee())
                + " | 데이터 " + (roaming.dataAmountMb() == null ? UNKNOWN : megabytes(roaming.dataAmountMb()));
    }

    // ---- UserDataTools와 같이 쓰는 형식 ----

    /** 회선을 고를 수 있게 마스킹한 번호와 상태만 보여 줍니다. SIM 정보는 넣지 않습니다. */
    static String formatSubscription(SubscriptionResponseDto subscription) {
        return maskPhone(subscription.phoneNumber())
                + " (" + subscriptionStatusLabel(subscription.status())
                + ", " + valueOrUnknown(toDate(subscription.activatedAt())) + " 개통"
                + ", 요금제 " + valueOrUnknown(subscription.planName()) + ")";
    }

    /** 010-1234-5678 → 010-****-5678. 끝 4자리만 남겨 사용자가 회선을 구분할 수 있게 합니다. */
    static String maskPhone(String phoneNumber) {
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
        if (digits.length() == 11) {
            return digits.substring(0, 3) + "-****-" + digits.substring(7);
        }
        if (digits.length() == 10) {
            return digits.substring(0, 3) + "-***-" + digits.substring(6);
        }
        if (digits.length() > 4) {
            return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
        }
        return "****";
    }

    /** usage_records.billing_month는 사용한 달입니다. */
    static String formatUsage(UsageRecordResponseDto usage) {
        UsageAllowanceResponseDto allowance = usage.allowance();
        String status;
        if (usage.finalized()) {
            status = "확정";
        } else if (usage.aggregatedThroughDate() != null) {
            status = usage.aggregatedThroughDate() + "까지 집계, 미확정";
        } else {
            status = "집계 전";
        }
        return usage.billingMonth() + " 사용량(" + status + "): "
                + "데이터 " + (usage.dataUsedMb() == null ? UNKNOWN : megabytes(usage.dataUsedMb())) + " 사용, 잔여 "
                + remaining(allowance == null ? null : allowance.dataUnlimited(),
                        usage.remainingDataMb() == null ? null : megabytes(usage.remainingDataMb()), allowance)
                + " | 음성 " + (usage.voiceUsedMinutes() == null ? UNKNOWN : usage.voiceUsedMinutes() + "분") + " 사용, 잔여 "
                + remaining(allowance == null ? null : allowance.voiceUnlimited(),
                        usage.remainingVoiceMinutes() == null ? null : usage.remainingVoiceMinutes() + "분", allowance)
                + " | 문자 " + (usage.smsUsedCount() == null ? UNKNOWN : usage.smsUsedCount() + "건") + " 사용, 잔여 "
                + remaining(allowance == null ? null : allowance.smsUnlimited(),
                        usage.remainingSmsCount() == null ? null : usage.remainingSmsCount() + "건", allowance);
    }

    /** 답변 첫머리에 그대로 옮겨 쓸 기준 문장입니다. 예: "2026년 10월 청구서(9월 사용분) 기준으로 안내드립니다." */
    static String billingBasis(BillingRecordResponseDto billing) {
        return billingTitle(billing) + " 기준으로 안내드립니다.";
    }

    /** billing_records.billing_month는 청구서가 나온 달이고, 금액은 전월 사용분입니다. 예: "2026년 10월 청구서(9월 사용분)" */
    private static String billingTitle(BillingRecordResponseDto billing) {
        return monthLabel(billing.billingMonth()) + " 청구서(" + billing.billingMonth().minusMonths(1).getMonthValue() + "월 사용분)";
    }

    /** 답변 문구와 같게 "2026년 10월"로 적습니다. 도구 인자는 YYYY-MM 그대로 받습니다. */
    private static String monthLabel(YearMonth month) {
        return month.getYear() + "년 " + month.getMonthValue() + "월";
    }

    static String formatBilling(BillingRecordResponseDto billing) {
        return formatBilling(billing, ": 총 ");
    }

    private static String formatBilling(BillingRecordResponseDto billing, String separator) {
        StringBuilder text = new StringBuilder();
        text.append(billingTitle(billing)).append(separator)
                .append(won(billing.totalAmount()))
                .append("(기본료 ").append(won(billing.baseFee()))
                .append(", 부가서비스 ").append(won(billing.addonFee()))
                .append(", 로밍 ").append(won(billing.roamingFee()))
                .append(", 기타 ").append(won(billing.extraFee()))
                .append(", 할인 -").append(won(billing.discountAmount())).append(")")
                .append(" | 납부 기한 ").append(valueOrUnknown(billing.dueDate()))
                .append(" | ").append(paymentStatusLabel(billing.paymentStatus()));
        if (billing.paymentStatus() == PaymentStatus.PAID) {
            text.append("(").append(valueOrUnknown(toDate(billing.paidAt())))
                    .append(", ").append(paymentMethodLabel(billing.paymentMethod())).append(")");
        } else if (billing.paidAmount() != null && billing.paidAmount() > 0) {
            text.append("(일부 납부 ").append(won(billing.paidAmount()))
                    .append(", 미납액 ").append(won(unpaidAmount(billing))).append(")");
        }
        return text.toString();
    }

    // ---- 내부 형식 ----

    private static String formatPlan(PlanSummaryResponseDto plan) {
        String data = plan.dataUnlimited() ? "무제한" : valueOrUnknown(plan.dataAmountMb() == null ? null : megabytes(plan.dataAmountMb()));
        if (!plan.dataUnlimited() && plan.exhaustedSpeedKbps() != null) {
            data += "(소진 후 최대 " + speed(plan.exhaustedSpeedKbps()) + ")";
        }
        return valueOrUnknown(plan.name())
                + " | 월정액 " + won(plan.monthlyFee())
                + " | 데이터 " + data
                + " | 음성 " + (plan.voiceUnlimited() ? "무제한" : valueOrUnknown(plan.voiceMinutes() == null ? null : plan.voiceMinutes() + "분"))
                + " | 문자 " + (plan.smsUnlimited() ? "무제한" : valueOrUnknown(plan.smsCount() == null ? null : plan.smsCount() + "건"));
    }

    private static String formatContract(SubscriptionContractResponseDto contract) {
        StringBuilder text = new StringBuilder();
        text.append(contractTypeLabel(contract.contractType()))
                .append(" ").append(valueOrUnknown(contract.startedAt())).append("~").append(valueOrUnknown(contract.expiresAt()))
                .append(" | 약정 할인 ").append(won(contract.discountAmount()));
        InstallmentResponseDto installment = contract.installment();
        if (installment == null) {
            return text.append(" | 단말 할부 없음").toString();
        }
        text.append(" | 단말 할부: ").append(valueOrUnknown(installment.deviceName()));
        if (installment.installmentStatus() == InstallmentStatus.COMPLETED) {
            return text.append(" 할부 완료").toString();
        }
        return text.append(", 월 ").append(won(installment.monthlyInstallmentAmount()))
                .append(", ").append(valueOrUnknown(installment.installmentMonths())).append("개월 중 ")
                .append(valueOrUnknown(installment.paidInstallmentMonths())).append("개월 납부")
                .append(", 잔여 ").append(won(installment.remainingInstallmentAmount()))
                .append(", 만료 ").append(valueOrUnknown(installment.installmentExpiresAt()))
                .toString();
    }

    private static String remaining(Boolean unlimited, String remaining, UsageAllowanceResponseDto allowance) {
        if (allowance == null) {
            return "제공량 정보 없음";
        }
        if (Boolean.TRUE.equals(unlimited)) {
            return "무제한";
        }
        return valueOrUnknown(remaining);
    }

    static int unpaidAmount(BillingRecordResponseDto billing) {
        int total = billing.totalAmount() == null ? 0 : billing.totalAmount();
        int paid = billing.paidAmount() == null ? 0 : billing.paidAmount();
        return Math.max(0, total - paid);
    }

    /** 1024MB 이상이면 GB로 소수 첫째 자리까지 보여 줍니다. */
    static String megabytes(long mb) {
        return mb >= 1024 ? String.format(Locale.ROOT, "%.1fGB", mb / 1024.0) : mb + "MB";
    }

    static String speed(int kbps) {
        return kbps >= 1000 && kbps % 1000 == 0 ? kbps / 1000 + "Mbps" : kbps + "Kbps";
    }

    static String won(Integer amount) {
        return amount == null ? UNKNOWN : String.format(Locale.ROOT, "%,d원", amount);
    }

    private static String subscriptionStatusLabel(SubscriptionStatus status) {
        if (status == null) return UNKNOWN;
        return switch (status) {
            case ACTIVE -> "이용 중";
            case SUSPENDED -> "일시정지";
            case TERMINATED -> "해지";
        };
    }

    private static String paymentStatusLabel(PaymentStatus status) {
        if (status == null) return UNKNOWN;
        return switch (status) {
            case PAID -> "납부 완료";
            case UNPAID -> "미납";
            case OVERDUE -> "연체";
        };
    }

    private static String paymentMethodLabel(PaymentMethod method) {
        if (method == null) return "납부 수단 정보 없음";
        return switch (method) {
            case CARD -> "카드";
            case ACCOUNT -> "계좌이체";
            case AUTO_TRANSFER -> "자동이체";
            case ETC -> "기타";
        };
    }

    private static String contractTypeLabel(ContractType type) {
        if (type == null) return "약정";
        return switch (type) {
            case SELECTIVE_CONTRACT -> "선택약정";
            case DEVICE_SUBSIDY -> "공시지원금 약정";
        };
    }

    static LocalDate toDate(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toLocalDate();
    }

    static String valueOrUnknown(Object value) {
        if (value instanceof String text) {
            return StringUtils.hasText(text) ? text : UNKNOWN;
        }
        return value == null ? UNKNOWN : value.toString();
    }
}
