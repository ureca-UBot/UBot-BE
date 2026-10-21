package com.ubot.ai.tool;

import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.billing.service.BillingService;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.service.SubscriptionService;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import com.ubot.usage.service.UsageService;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * UserDataSummarizer가 미리 넣지 않은 지난 달 사용량·청구서를 LLM이 필요할 때 조회하는 도구입니다.
 * 미리 넣은 정보와 겹치지 않게, 요약에 들어간 가장 최근 달은 다시 돌려주지 않습니다.
 * 사용자 ID는 LLM이 아니라 서버가 ToolContext로 넣고, 회선 소유권은 각 서비스가 다시 확인합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDataTools {

    // AiService가 toolContext를 만들 때와 이 클래스가 읽을 때 같은 키를 씁니다.
    public static final String USER_ID = "userId";
    public static final String RECORDER = "userDataRecorder";
    // 미리 조회한 요약이 프롬프트에 들어갔을 때만 true로 넣습니다. 요약이 실패했으면 가장 최근 달도 도구가 돌려줍니다.
    public static final String SUMMARY_PROVIDED = "userDataSummaryProvided";

    static final int LOOKUP_MONTHS = 12;

    static final String LOGIN_REQUIRED_MESSAGE =
            "로그인한 사용자만 가입 정보를 조회할 수 있습니다. 로그인 후 다시 질문하도록 안내하세요.";
    static final String LOOKUP_FAILED_MESSAGE = "가입 정보를 조회하지 못했습니다. 잠시 후 다시 시도하도록 안내하세요.";
    static final String NO_SUBSCRIPTION_MESSAGE = "이용 중인 회선이 없습니다.";
    static final String SELECT_SUBSCRIPTION_MESSAGE =
            "어느 회선인지 정할 수 없습니다. 번호를 추측하지 말고, 아래 회선 중 어느 번호인지 사용자에게 물어보세요.\n%s";
    static final String SUBSCRIPTION_NOT_MATCHED_MESSAGE =
            "끝자리가 %s인 회선이 없습니다. 번호를 추측하지 말고, 아래 회선 중 어느 번호인지 사용자에게 물어보세요.\n%s";
    static final String INVALID_MONTH_MESSAGE = "'%s'은(는) 올바른 월이 아닙니다. month를 YYYY-MM 형식으로 넣어 다시 호출하세요.";
    static final String FUTURE_MONTH_MESSAGE = "%s은(는) 아직 오지 않은 달이라 기록이 없습니다. 조회 기준일은 %s입니다.";
    static final String TOO_OLD_MONTH_MESSAGE = "최근 12개월(%s~%s) 기록만 조회할 수 있습니다.";
    static final String ALREADY_PROVIDED_MESSAGE =
            "%s %s은(는) 가장 최근 기록이라 사용자 정보 섹션에 이미 있습니다. 도구를 다시 호출하지 말고 그 내용으로 답하세요.";
    // 도구 결과는 호출을 정한 뒤에 읽으므로, 여기 둔 안내는 도구 호출 여부에 영향을 주지 않습니다.
    static final String BILLING_BASIS_MESSAGE = "답변은 \"%s\"로 시작하세요.";

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("uuuu-M");

    private final SubscriptionService subscriptionService;
    private final UsageService usageService;
    private final BillingService billingService;
    private final Clock clock;

    @Tool(description = "사용자 회선의 지난 월 데이터·음성·문자 사용량과 잔여량을 조회한다. "
            + "가장 최근 달의 사용량은 미리 제공된 사용자 정보에 있으므로 호출하지 않는다. 그보다 이전 달을 물을 때만 호출한다.")
    public String getUsageByMonth(
            @ToolParam(description = "사용한 달. YYYY-MM 형식. 예: 2026-09") String month,
            @ToolParam(description = "조회할 회선 전화번호 끝 4자리. 회선이 하나면 비운다.", required = false) String phoneLast4,
            ToolContext toolContext) {
        UserDataRecorder recorder = recorder(toolContext);
        return lookupByMonth(month, phoneLast4, toolContext, "사용량",
                (userId, subscription) -> usageService.getMyUsageRecordList(
                        userId, subscription.subscriptionId(), null, 0, LOOKUP_MONTHS).content(),
                UsageRecordResponseDto::billingMonth, UserDataSummarizer::formatUsage,
                (subscription, usage) -> recorder.recordUsage(
                        UserDataSummarizer.maskPhone(subscription.phoneNumber()), usage));
    }

    @Tool(description = "사용자 회선의 지난 청구서(청구 금액, 항목별 요금, 납부 기한, 납부 상태)를 조회한다. "
            + "청구서는 전월 사용분을 그달에 청구한다. 가장 최근 청구서와 미납 내역은 미리 제공된 사용자 정보에 있으므로 호출하지 않는다. "
            + "그보다 이전 청구 월의 상세 내역을 물을 때만 호출한다.")
    public String getBillingByMonth(
            @ToolParam(description = "청구서가 나온 달. YYYY-MM 형식. 예: 2026-09") String month,
            @ToolParam(description = "조회할 회선 전화번호 끝 4자리. 회선이 하나면 비운다.", required = false) String phoneLast4,
            ToolContext toolContext) {
        UserDataRecorder recorder = recorder(toolContext);
        return lookupByMonth(month, phoneLast4, toolContext, "청구서",
                (userId, subscription) -> billingService.getMyBillingRecordList(
                        userId, subscription.subscriptionId(), null, 0, LOOKUP_MONTHS).content(),
                BillingRecordResponseDto::billingMonth, billing -> UserDataSummarizer.formatBilling(billing) + "\n"
                        + String.format(BILLING_BASIS_MESSAGE, UserDataSummarizer.billingBasis(billing)),
                (subscription, billing) -> recorder.recordBilling(
                        UserDataSummarizer.maskPhone(subscription.phoneNumber()), billing));
    }

    /** AiService가 넣은 기록 보관소입니다. 평가처럼 넣지 않은 호출에서는 버려지는 보관소를 씁니다. */
    private UserDataRecorder recorder(ToolContext toolContext) {
        return toolContext != null && toolContext.getContext().get(RECORDER) instanceof UserDataRecorder value
                ? value : new UserDataRecorder();
    }

    /**
     * recentRecords는 최신 월부터 정렬된 최근 기록입니다. 첫 건은 UserDataSummarizer가 미리 넣은 기록이므로 다시 돌려주지 않습니다.
     * 다만 요약이 실패해 프롬프트에 없으면 첫 건도 다른 달처럼 돌려줍니다.
     * 기록은 한 달에 한 건이라 최근 12건이면 조회 가능한 12개월을 모두 덮습니다.
     * 답변에 쓰일 기록은 화면 카드로도 보내도록 onFound로 남깁니다. 미리 넣은 달을 다시 물은 경우도 그 기록으로 답하므로 남깁니다.
     */
    private <T> String lookupByMonth(String month, String phoneLast4, ToolContext toolContext, String recordName,
            BiFunction<Long, SubscriptionResponseDto, List<T>> recentRecords,
            Function<T, YearMonth> monthOf, Function<T, String> formatter,
            BiConsumer<SubscriptionResponseDto, T> onFound) {
        YearMonth current = YearMonth.from(LocalDate.now(clock));
        YearMonth requested;
        try {
            requested = YearMonth.parse(month == null ? "" : month.strip(), MONTH_FORMAT);
        } catch (DateTimeParseException exception) {
            return String.format(INVALID_MONTH_MESSAGE, month);
        }
        if (requested.isAfter(current)) {
            return String.format(FUTURE_MONTH_MESSAGE, requested, LocalDate.now(clock));
        }
        YearMonth oldest = current.minusMonths(LOOKUP_MONTHS - 1);
        if (requested.isBefore(oldest)) {
            return String.format(TOO_OLD_MONTH_MESSAGE, oldest, current);
        }
        boolean summaryProvided = Boolean.TRUE.equals(toolContext.getContext().get(SUMMARY_PROVIDED));
        return lookup(phoneLast4, toolContext, (userId, subscription) -> {
            List<T> records = recentRecords.apply(userId, subscription);
            if (summaryProvided && !records.isEmpty() && monthOf.apply(records.getFirst()).equals(requested)) {
                onFound.accept(subscription, records.getFirst());
                return String.format(ALREADY_PROVIDED_MESSAGE, requested, recordName);
            }
            Optional<T> found = records.stream()
                    .filter(record -> monthOf.apply(record).equals(requested))
                    .findFirst();
            found.ifPresent(record -> onFound.accept(subscription, record));
            return found.map(formatter).orElse(requested + " " + recordName + " 기록이 없습니다.");
        });
    }

    /** 사용자와 회선을 확인한 뒤 조회합니다. 조회 오류는 답변 전체 실패로 번지지 않게 안내 문구로 돌려줍니다. */
    private String lookup(String phoneLast4, ToolContext toolContext, BiFunction<Long, SubscriptionResponseDto, String> query) {
        Long userId = toolContext.getContext().get(USER_ID) instanceof Long value ? value : null;
        if (userId == null) {
            return LOGIN_REQUIRED_MESSAGE;
        }
        try {
            List<SubscriptionResponseDto> subscriptions =
                    UserDataSummarizer.getInUseSubscriptionList(subscriptionService, userId);
            if (subscriptions.isEmpty()) {
                return NO_SUBSCRIPTION_MESSAGE;
            }

            String suffix = phoneLast4 == null ? "" : phoneLast4.replaceAll("\\D", "");
            if (suffix.isEmpty()) {
                if (subscriptions.size() > 1) {
                    return String.format(SELECT_SUBSCRIPTION_MESSAGE, formatSubscriptionList(subscriptions));
                }
                return respond(subscriptions.getFirst(), query.apply(userId, subscriptions.getFirst()));
            }

            List<SubscriptionResponseDto> matched = subscriptions.stream()
                    .filter(subscription -> digits(subscription.phoneNumber()).endsWith(suffix))
                    .toList();
            if (matched.isEmpty()) {
                return String.format(SUBSCRIPTION_NOT_MATCHED_MESSAGE, suffix, formatSubscriptionList(subscriptions));
            }
            if (matched.size() > 1) {
                return String.format(SELECT_SUBSCRIPTION_MESSAGE, formatSubscriptionList(matched));
            }
            return respond(matched.getFirst(), query.apply(userId, matched.getFirst()));
        } catch (RuntimeException exception) {
            log.warn("가입 정보 조회 도구 실패: userId={}, phoneLast4={}", userId, phoneLast4, exception);
            return LOOKUP_FAILED_MESSAGE;
        }
    }

    private String respond(SubscriptionResponseDto subscription, String result) {
        return UserDataSummarizer.maskPhone(subscription.phoneNumber()) + " 회선\n" + result;
    }

    private String formatSubscriptionList(List<SubscriptionResponseDto> subscriptions) {
        return subscriptions.stream()
                .map(subscription -> "- " + UserDataSummarizer.formatSubscription(subscription))
                .collect(Collectors.joining("\n"));
    }

    private String digits(String value) {
        return StringUtils.hasText(value) ? value.replaceAll("\\D", "") : "";
    }
}
