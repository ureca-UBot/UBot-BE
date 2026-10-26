package com.ubot.ai.tool;

import com.ubot.ai.dto.LookupFailure;
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
    // 이번 질문 원문입니다. "8월 9월 요금"처럼 여러 달을 물었는데 모델이 한 달만 조회하는 일이 있어, 질문에 나온 다른 달도 함께 돌려줍니다.
    public static final String CURRENT_QUESTION = "userDataCurrentQuestion";

    static final int LOOKUP_MONTHS = 12;
    // 질문에서 함께 조회할 달의 최대 수입니다(모델이 조회한 달 포함). 결과가 길어져 컨텍스트를 넘지 않게 제한합니다.
    static final int MAX_QUESTION_MONTHS = 3;

    // 조회하지 못한 사유별 안내(로그인, 다시 시도, 회선 선택)는 코드가 붙이므로(AiService) 모델에게 쓰게 하지 않습니다.
    static final String LOGIN_REQUIRED_MESSAGE = "로그인한 사용자만 가입 정보를 조회할 수 있습니다.";
    static final String LOOKUP_FAILED_MESSAGE = "가입 정보를 조회하지 못했습니다. 가입 정보를 추측하지 마세요.";
    static final String NO_SUBSCRIPTION_MESSAGE = "이용 중인 회선이 없습니다.";
    static final String SELECT_SUBSCRIPTION_MESSAGE =
            "어느 회선인지 정할 수 없습니다. 번호를 추측하지 마세요. 이용 중인 회선:\n%s";
    static final String SUBSCRIPTION_NOT_MATCHED_MESSAGE =
            "끝자리가 %s인 회선이 없습니다. 번호를 추측하지 마세요. 이용 중인 회선:\n%s";
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
        return withQuestionMonths(month, toolContext, false, requested -> lookupByMonth(requested, phoneLast4, toolContext,
                "사용량",
                (userId, subscription) -> usageService.getMyUsageRecordList(
                        userId, subscription.subscriptionId(), null, 0, LOOKUP_MONTHS).content(),
                UsageRecordResponseDto::billingMonth, UserDataSummarizer::formatUsage,
                (subscription, usage) -> recorder.recordUsage(
                        UserDataSummarizer.maskPhone(subscription.phoneNumber()), usage)));
    }

    @Tool(description = "사용자 회선의 지난 청구서(청구 금액, 항목별 요금, 납부 기한, 납부 상태)를 조회한다. "
            + "청구서는 전월 사용분을 그달에 청구한다. 가장 최근 청구서와 미납 내역은 미리 제공된 사용자 정보에 있으므로 호출하지 않는다. "
            + "그보다 이전 청구 월의 상세 내역을 물을 때만 호출한다.")
    public String getBillingByMonth(
            @ToolParam(description = "청구서가 나온 달. YYYY-MM 형식. 예: 2026-09") String month,
            @ToolParam(description = "조회할 회선 전화번호 끝 4자리. 회선이 하나면 비운다.", required = false) String phoneLast4,
            ToolContext toolContext) {
        UserDataRecorder recorder = recorder(toolContext);
        return withQuestionMonths(month, toolContext, true, requested -> lookupByMonth(requested, phoneLast4, toolContext,
                "청구서",
                (userId, subscription) -> billingService.getMyBillingRecordList(
                        userId, subscription.subscriptionId(), null, 0, LOOKUP_MONTHS).content(),
                BillingRecordResponseDto::billingMonth, billing -> UserDataSummarizer.formatBilling(billing) + "\n"
                        + String.format(BILLING_BASIS_MESSAGE, UserDataSummarizer.billingBasis(billing)),
                (subscription, billing) -> recorder.recordBilling(
                        UserDataSummarizer.maskPhone(subscription.phoneNumber()), billing)));
    }

    /**
     * 모델이 넣은 달을 조회하고, 질문에 둘 이상의 달이 나왔으면 아직 조회하지 않은 달도 같은 방식으로 조회해 이어 붙입니다.
     * 질문에 달이 하나뿐이면 모델이 고른 달("9월 사용분" → 10월 청구서)을 그대로 따릅니다.
     * 청구서는 "9월 사용분"처럼 사용 월로 말한 달이 있으면 청구 월로 바꾸는 판단이 모델 몫이라 더 붙이지 않습니다.
     */
    private String withQuestionMonths(String month, ToolContext toolContext, boolean billing,
            Function<String, String> lookupMonth) {
        String result = lookupMonth.apply(month);
        List<YearMonth> others = otherQuestionMonths(month, toolContext, billing);
        if (others.isEmpty()) {
            return result;
        }
        log.debug("가입 정보 조회 도구: 질문에 나온 다른 달도 함께 조회합니다: {}", others);
        StringBuilder text = new StringBuilder(result);
        others.forEach(other -> text.append("\n\n").append(lookupMonth.apply(other.toString())));
        return text.toString();
    }

    /** 질문 속 달은 QuestionMonths로 찾습니다("8월 9월", "8~10월", "최근 3개월"). */
    private List<YearMonth> otherQuestionMonths(String month, ToolContext toolContext, boolean billing) {
        String question = toolContext == null ? null
                : toolContext.getContext().get(CURRENT_QUESTION) instanceof String value ? value : null;
        if (!StringUtils.hasText(question)) {
            return List.of();
        }
        YearMonth asked;
        try {
            asked = YearMonth.parse(month == null ? "" : month.strip(), MONTH_FORMAT);
        } catch (DateTimeParseException exception) {
            return List.of();
        }
        QuestionMonths.Found found = QuestionMonths.find(question, YearMonth.from(LocalDate.now(clock)), MAX_QUESTION_MONTHS);
        if (found.months().size() < 2 || billing && found.usageMonthMentioned()) {
            return List.of();
        }
        return found.months().stream().filter(other -> !other.equals(asked)).toList();
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
        // 호출 내역은 원인 분석용이라 DEBUG로만 남깁니다. 전화번호와 금액은 남기지 않고 회선 지정 여부만 남깁니다.
        boolean lineSpecified = StringUtils.hasText(phoneLast4);
        YearMonth current = YearMonth.from(LocalDate.now(clock));
        YearMonth requested;
        try {
            requested = YearMonth.parse(month == null ? "" : month.strip(), MONTH_FORMAT);
        } catch (DateTimeParseException exception) {
            log.debug("가입 정보 조회 도구: 종류={}, 회선지정={}, 결과=달 형식 오류", recordName, lineSpecified);
            return String.format(INVALID_MONTH_MESSAGE, month);
        }
        if (requested.isAfter(current)) {
            log.debug("가입 정보 조회 도구: 종류={}, month={}, 회선지정={}, 결과=미래 달", recordName, requested, lineSpecified);
            return String.format(FUTURE_MONTH_MESSAGE, requested, LocalDate.now(clock));
        }
        YearMonth oldest = current.minusMonths(LOOKUP_MONTHS - 1);
        if (requested.isBefore(oldest)) {
            log.debug("가입 정보 조회 도구: 종류={}, month={}, 회선지정={}, 결과=조회 기간 밖", recordName, requested,
                    lineSpecified);
            return String.format(TOO_OLD_MONTH_MESSAGE, oldest, current);
        }
        boolean summaryProvided = Boolean.TRUE.equals(toolContext.getContext().get(SUMMARY_PROVIDED));
        return lookup(phoneLast4, toolContext, (userId, subscription) -> {
            List<T> records = recentRecords.apply(userId, subscription);
            if (summaryProvided && !records.isEmpty() && monthOf.apply(records.getFirst()).equals(requested)) {
                log.debug("가입 정보 조회 도구: 종류={}, month={}, 회선지정={}, 결과=미리 제공된 달", recordName, requested,
                        lineSpecified);
                onFound.accept(subscription, records.getFirst());
                return String.format(ALREADY_PROVIDED_MESSAGE, requested, recordName);
            }
            Optional<T> found = records.stream()
                    .filter(record -> monthOf.apply(record).equals(requested))
                    .findFirst();
            log.debug("가입 정보 조회 도구: 종류={}, month={}, 회선지정={}, 결과={}", recordName, requested, lineSpecified,
                    found.isPresent() ? "조회됨" : "기록 없음");
            found.ifPresent(record -> onFound.accept(subscription, record));
            return found.map(formatter).orElse(requested + " " + recordName + " 기록이 없습니다.");
        });
    }

    /** 사용자와 회선을 확인한 뒤 조회합니다. 조회 오류는 답변 전체 실패로 번지지 않게 안내 문구로 돌려줍니다. */
    private String lookup(String phoneLast4, ToolContext toolContext, BiFunction<Long, SubscriptionResponseDto, String> query) {
        UserDataRecorder recorder = recorder(toolContext);
        Long userId = toolContext.getContext().get(USER_ID) instanceof Long value ? value : null;
        if (userId == null) {
            recorder.fail(LookupFailure.of(LookupFailure.Kind.LOGIN_REQUIRED));
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
                    recorder.fail(LookupFailure.of(LookupFailure.Kind.SUBSCRIPTION_UNSELECTED, phoneList(subscriptions)));
                    return String.format(SELECT_SUBSCRIPTION_MESSAGE, formatSubscriptionList(subscriptions));
                }
                return respond(subscriptions.getFirst(), query.apply(userId, subscriptions.getFirst()));
            }

            List<SubscriptionResponseDto> matched = subscriptions.stream()
                    .filter(subscription -> digits(subscription.phoneNumber()).endsWith(suffix))
                    .toList();
            if (matched.isEmpty()) {
                recorder.fail(LookupFailure.of(LookupFailure.Kind.SUBSCRIPTION_NOT_MATCHED, suffix, phoneList(subscriptions)));
                return String.format(SUBSCRIPTION_NOT_MATCHED_MESSAGE, suffix, formatSubscriptionList(subscriptions));
            }
            if (matched.size() > 1) {
                recorder.fail(LookupFailure.of(LookupFailure.Kind.SUBSCRIPTION_UNSELECTED, phoneList(matched)));
                return String.format(SELECT_SUBSCRIPTION_MESSAGE, formatSubscriptionList(matched));
            }
            return respond(matched.getFirst(), query.apply(userId, matched.getFirst()));
        } catch (RuntimeException exception) {
            log.warn("가입 정보 조회 도구 실패: userId={}, phoneLast4={}", userId, phoneLast4, exception);
            recorder.fail(LookupFailure.of(LookupFailure.Kind.USER_LOOKUP_FAILED));
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

    /** 사용자에게 보여 줄 회선 목록입니다. 번호는 가리고 요금제만 덧붙입니다. */
    private String phoneList(List<SubscriptionResponseDto> subscriptions) {
        return subscriptions.stream()
                .map(subscription -> UserDataSummarizer.maskPhone(subscription.phoneNumber())
                        + (StringUtils.hasText(subscription.planName()) ? "(" + subscription.planName() + ")" : ""))
                .collect(Collectors.joining(", "));
    }

    private String digits(String value) {
        return StringUtils.hasText(value) ? value.replaceAll("\\D", "") : "";
    }
}
