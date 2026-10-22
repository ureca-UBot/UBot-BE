package com.ubot.ai.tool;

import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.dto.UserDataSummary;
import com.ubot.ai.dto.UserDataSummary.SubscriptionSummary;
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.contract.dto.response.InstallmentResponseDto;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 답변 문장의 청구 금액·데이터량을 회원의 실제 기록(가입 정보 요약과 도구로 조회한 사용량·청구서)과 비교합니다. 출력 가드가 씁니다.
 * 기록의 금액을 모두 하나의 "요금"으로 모으면 다른 달 청구 금액이나 월정액·할인액으로 바꿔 말한 답을 가리지 못하므로,
 * 문장에 나온 회선·월·항목(총액·기본료·할인 등)에 해당하는 기록 값만 허용합니다.
 * 회선·월·항목을 문장에서 알 수 없으면 그 조건으로는 거르지 않습니다.
 */
public final class UserRecordClaims {

    private static final UserRecordClaims EMPTY = new UserRecordClaims(List.of(), List.of(), List.of());

    // "2026년 10월", "10월 청구서", "9월 사용분". "10월 25일"처럼 날짜인 경우는 월로 보지 않습니다.
    private static final Pattern MONTH = Pattern.compile(
            "(?:(\\d{4})\\s*년\\s*)?(?<!\\d)(1[0-2]|0?[1-9])\\s*월(?!\\s*\\d{1,2}\\s*일)(\\s*사용(?:분|량|한)?|\\s*청구)?");
    private static final Pattern THIS_MONTH = Pattern.compile("이번\\s*달|이달|당월");
    private static final Pattern LAST_MONTH = Pattern.compile("지난\\s*달|저번\\s*달|전월|지난번\\s*달");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(\\d{4})\\s*회선|\\*{2,}-?(\\d{4})(?!\\d)");
    // 원 단위 금액: "78,000원", "7만 8,000원", "7만원"
    private static final Pattern MONEY = Pattern.compile(
            "(?<![\\d.,])(?:(\\d{1,4})\\s*만\\s*)?(\\d{1,3}(?:,\\d{3})+|\\d+)?\\s*원");
    private static final Pattern DATA = Pattern.compile(
            "(?<![\\d.,])(\\d+(?:\\.\\d+)?)\\s*(GB|MB|기가바이트|메가바이트|기가)", Pattern.CASE_INSENSITIVE);

    // 청구서 금액을 말하는 문장의 표지입니다. "요금"·"금액"은 월과 함께 나올 때만 청구 금액으로 봅니다.
    private static final Pattern BILL_MARKER = Pattern.compile("청구|미납|납부");
    private static final Pattern BILL_WITH_MONTH = Pattern.compile("요금|금액");
    private static final Pattern USAGE_MARKER = Pattern.compile("사용|쓰셨|쓴|남(?:았|은|아)|잔여|제공|소진");

    private static final List<ItemKeyword> MONEY_ITEMS = List.of(
            new ItemKeyword(MoneyItem.BASE, Pattern.compile("기본\\s*료|기본\\s*요금")),
            new ItemKeyword(MoneyItem.ADDON, Pattern.compile("부가\\s*서비스")),
            new ItemKeyword(MoneyItem.ROAMING, Pattern.compile("로밍")),
            new ItemKeyword(MoneyItem.EXTRA, Pattern.compile("기타")),
            new ItemKeyword(MoneyItem.DISCOUNT, Pattern.compile("할인(?!\\s*(?:후|된|받은|적용\\s*후))")),
            new ItemKeyword(MoneyItem.UNPAID, Pattern.compile("미납")),
            new ItemKeyword(MoneyItem.PAID, Pattern.compile("납부(?:한|하신|하셨|액|\\s*금액)|결제(?:한|하신|된)")),
            new ItemKeyword(MoneyItem.PLAN_FEE, Pattern.compile("월정액|요금제")),
            new ItemKeyword(MoneyItem.INSTALLMENT, Pattern.compile("할부")));
    private static final Pattern TOTAL_KEYWORD = Pattern.compile("총|청구|요금(?!제)|금액");
    private static final List<ItemKeyword> DATA_ITEMS = List.of(
            new ItemKeyword(DataItem.USED, Pattern.compile("사용(?!\\s*가능)|쓰셨|쓴")),
            new ItemKeyword(DataItem.REMAINING, Pattern.compile("남(?:았|은|아)|잔여")),
            new ItemKeyword(DataItem.ALLOWANCE, Pattern.compile("제공|요금제")));
    private static final int KEYWORD_WINDOW = 20;
    // 요약은 GB를 소수 첫째 자리까지 보여 주므로 그 반올림 차이만큼은 같은 값으로 봅니다.
    private static final double GB_TOLERANCE_MB = 0.05 * 1024 + 1;

    private final List<Bill> bills;
    private final List<Usage> usages;
    private final List<SubscriptionSummary> subscriptions;

    private UserRecordClaims(List<Bill> bills, List<Usage> usages, List<SubscriptionSummary> subscriptions) {
        this.bills = bills;
        this.usages = usages;
        this.subscriptions = subscriptions;
    }

    /** 요약의 최근·미납 청구서와 최근 사용량, 도구로 조회한 사용량·청구서를 모읍니다. 둘 다 없으면 빈 기록입니다. */
    public static UserRecordClaims of(UserDataSummary summary, UserDataCards toolRecords) {
        List<Bill> bills = new ArrayList<>();
        List<Usage> usages = new ArrayList<>();
        List<SubscriptionSummary> subscriptions = summary == null ? List.of() : summary.subscriptions();
        for (SubscriptionSummary subscription : subscriptions) {
            String phone = digits(subscription.subscription().phoneNumber());
            if (subscription.latestBilling() != null) bills.add(new Bill(phone, subscription.latestBilling(), true));
            subscription.unpaidBillings().forEach(billing -> bills.add(new Bill(phone, billing, false)));
            if (subscription.latestUsage() != null) usages.add(new Usage(phone, subscription.latestUsage(), true));
        }
        if (toolRecords != null) {
            toolRecords.billings().forEach(card -> bills.add(new Bill(digits(card.phoneNumber()), card.billing(), false)));
            toolRecords.usages().forEach(card -> usages.add(new Usage(digits(card.phoneNumber()), card.usage(), false)));
        }
        return bills.isEmpty() && usages.isEmpty() && subscriptions.isEmpty()
                ? EMPTY : new UserRecordClaims(List.copyOf(bills), List.copyOf(usages), subscriptions);
    }

    /**
     * 문장이 가리키는 청구서가 기록에 있는지 봅니다. 문장에 월이 있으면 그 달의 청구서가, 회선이 있으면 그 회선의 청구서가 있어야 합니다.
     * 월을 말하지 않았으면 청구서가 하나라도 있으면 참입니다.
     */
    public boolean hasReferencedBill(String sentence) {
        List<Bill> candidates = byPhone(sentence, bills, Bill::phone);
        List<MonthRef> refs = monthRefs(sentence);
        if (refs.isEmpty()) return !candidates.isEmpty();
        Set<YearMonthMatch> months = refs.stream().flatMap(ref -> billMonths(ref).stream()).collect(Collectors.toSet());
        return candidates.stream().anyMatch(bill -> matchesAny(months, bill.billing().billingMonth()));
    }

    /**
     * 문장의 청구 금액·데이터량을 기록과 비교합니다. 기록과 맞는 값과 맞지 않는 값은 판정한 것으로 보고 문장에서 지운 문장(unhandled)을
     * 돌려줍니다. 출력 가드의 일반 수치 검사는 그 문장으로 나머지 값만 봅니다.
     * 기록과 맞지 않아도 FAQ 등 다른 자료(otherSources)에 그대로 있는 값은 판정하지 않고 일반 수치 검사에 맡깁니다.
     */
    public Verdict check(String sentence, List<String> otherSources) {
        if (this == EMPTY || sentence == null || sentence.isBlank()) return new Verdict(sentence, false);
        List<int[]> handled = new ArrayList<>();
        boolean conflict = false;
        List<MonthRef> refs = monthRefs(sentence);
        boolean billSentence = !bills.isEmpty() && (BILL_MARKER.matcher(sentence).find()
                || !refs.isEmpty() && BILL_WITH_MONTH.matcher(sentence).find());
        if (billSentence) {
            Matcher money = MONEY.matcher(sentence);
            int previousEnd = 0;
            while (money.find()) {
                Long value = wonValue(money);
                if (value == null) continue;
                Set<Long> allowed = allowedMoney(sentence, refs, money.start(), previousEnd);
                previousEnd = money.end();
                if (allowed.contains(value)) {
                    handled.add(new int[] {money.start(), money.end()});
                } else if (!appearsIn(money.group().strip(), otherSources)) {
                    handled.add(new int[] {money.start(), money.end()});
                    conflict = true;
                }
            }
        }
        boolean usageSentence = !usages.isEmpty() && USAGE_MARKER.matcher(sentence).find();
        if (usageSentence) {
            Matcher data = DATA.matcher(sentence);
            int previousEnd = 0;
            while (data.find()) {
                double claimed = toMegabytes(data);
                List<Long> allowed = allowedData(sentence, refs, data, previousEnd);
                previousEnd = data.end();
                boolean gigabytes = !data.group(2).toUpperCase().startsWith("M") && !data.group(2).startsWith("메");
                double tolerance = gigabytes ? GB_TOLERANCE_MB : 0.5;
                if (allowed.stream().anyMatch(mb -> Math.abs(mb - claimed) <= tolerance)) {
                    handled.add(new int[] {data.start(), data.end()});
                } else if (!appearsIn(data.group().strip(), otherSources)) {
                    handled.add(new int[] {data.start(), data.end()});
                    conflict = true;
                }
            }
        }
        return new Verdict(mask(sentence, handled), conflict);
    }

    /** 판정 결과입니다. unhandled는 판정한 값을 지운 문장입니다. */
    public record Verdict(String unhandled, boolean conflict) {
    }

    // ---- 금액 ----

    private Set<Long> allowedMoney(String sentence, List<MonthRef> refs, int start, int previousEnd) {
        MoneyItem item = moneyItem(sentence, start, previousEnd);
        List<MonthRef> scoped = refsFor(refs, start);
        List<Bill> scopedBills = byPhone(sentence, bills, Bill::phone).stream()
                .filter(bill -> scoped.isEmpty() || scoped.stream()
                        .anyMatch(ref -> matchesAny(Set.copyOf(billMonths(ref)), bill.billing().billingMonth())))
                .toList();
        List<SubscriptionSummary> scopedSubscriptions = byPhone(sentence, subscriptions,
                subscription -> digits(subscription.subscription().phoneNumber()));
        Stream<Integer> values = switch (item) {
            case PLAN_FEE -> scopedSubscriptions.stream()
                    .filter(subscription -> subscription.plan() != null)
                    .map(subscription -> subscription.plan().summary().monthlyFee());
            case INSTALLMENT -> scopedSubscriptions.stream().flatMap(UserRecordClaims::installmentAmounts);
            case ANY -> Stream.concat(scopedBills.stream().flatMap(bill -> billAmounts(bill.billing())),
                    scopedSubscriptions.stream().flatMap(UserRecordClaims::subscriptionAmounts));
            default -> scopedBills.stream().map(bill -> billAmount(bill.billing(), item));
        };
        return values.filter(Objects::nonNull).map(Integer::longValue).collect(Collectors.toSet());
    }

    /**
     * 금액 바로 앞(이전 금액 뒤부터)에 나온 항목 이름 중 가장 가까운 것으로 항목을 정합니다. 이름이 없으면 어느 항목인지 모르는 것으로 봅니다.
     * "로밍 요금 11,000원"처럼 "요금"·"금액" 바로 앞에 항목 이름이 붙어 있으면 그 항목입니다.
     */
    private MoneyItem moneyItem(String sentence, int start, int previousEnd) {
        String window = sentence.substring(Math.max(previousEnd, start - KEYWORD_WINDOW), start);
        int[] specificAt = {-1};
        MoneyItem specific = lastMoneyItem(window, specificAt);
        int totalAt = -1;
        Matcher total = TOTAL_KEYWORD.matcher(window);
        while (total.find()) totalAt = total.start();
        if (totalAt > specificAt[0]) {
            int[] attachedAt = {-1};
            MoneyItem attached = lastMoneyItem(window.substring(Math.max(0, totalAt - 8), totalAt), attachedAt);
            return attached != null ? attached : MoneyItem.TOTAL;
        }
        return specific != null ? specific : MoneyItem.ANY;
    }

    private static MoneyItem lastMoneyItem(String window, int[] foundAt) {
        MoneyItem found = null;
        for (ItemKeyword keyword : MONEY_ITEMS) {
            Matcher matcher = keyword.pattern().matcher(window);
            while (matcher.find()) {
                if (matcher.start() > foundAt[0]) {
                    foundAt[0] = matcher.start();
                    found = (MoneyItem) keyword.item();
                }
            }
        }
        return found;
    }

    private static Integer billAmount(BillingRecordResponseDto billing, MoneyItem item) {
        return switch (item) {
            case TOTAL -> billing.totalAmount();
            case BASE -> billing.baseFee();
            case ADDON -> billing.addonFee();
            case ROAMING -> billing.roamingFee();
            case EXTRA -> billing.extraFee();
            case DISCOUNT -> billing.discountAmount();
            case PAID -> billing.paidAmount();
            case UNPAID -> UserDataSummarizer.unpaidAmount(billing);
            default -> null;
        };
    }

    private static Stream<Integer> billAmounts(BillingRecordResponseDto billing) {
        return Stream.of(billing.totalAmount(), billing.baseFee(), billing.addonFee(), billing.roamingFee(),
                billing.extraFee(), billing.discountAmount(), billing.paidAmount(), UserDataSummarizer.unpaidAmount(billing));
    }

    /** 항목을 모를 때 함께 허용하는 회선의 금액입니다. 요금제 월정액, 약정·결합 할인, 할부, 부가서비스·로밍 요금입니다. */
    private static Stream<Integer> subscriptionAmounts(SubscriptionSummary subscription) {
        return Stream.of(
                Stream.of(subscription.plan() == null ? null : subscription.plan().summary().monthlyFee()),
                Stream.of(subscription.activeContract() == null ? null : subscription.activeContract().discountAmount()),
                installmentAmounts(subscription),
                subscription.addons().stream().map(addon -> addon.monthlyFee()),
                subscription.bundles().stream().map(bundle -> bundle.appliedDiscountAmount()),
                subscription.roamings().stream().map(roaming -> roaming.dailyFee())).flatMap(stream -> stream);
    }

    private static Stream<Integer> installmentAmounts(SubscriptionSummary subscription) {
        InstallmentResponseDto installment = subscription.activeContract() == null ? null
                : subscription.activeContract().installment();
        return installment == null ? Stream.empty() : Stream.of(installment.monthlyInstallmentAmount(),
                installment.remainingInstallmentAmount(), installment.deviceTotalAmount());
    }

    // ---- 데이터 ----

    private List<Long> allowedData(String sentence, List<MonthRef> refs, Matcher data, int previousEnd) {
        DataItem item = dataItem(sentence, data.start(), data.end(), previousEnd);
        List<MonthRef> scoped = refsFor(refs, data.start());
        List<Usage> scopedUsages = byPhone(sentence, usages, Usage::phone).stream()
                .filter(usage -> scoped.isEmpty() || scoped.stream()
                        .anyMatch(ref -> matchesAny(Set.copyOf(usageMonths(ref)), usage.usage().billingMonth())))
                .toList();
        List<SubscriptionSummary> scopedSubscriptions = byPhone(sentence, subscriptions,
                subscription -> digits(subscription.subscription().phoneNumber()));
        Stream<Long> values = switch (item) {
            case USED -> scopedUsages.stream().map(usage -> usage.usage().dataUsedMb());
            case REMAINING -> scopedUsages.stream().map(usage -> usage.usage().remainingDataMb());
            case ALLOWANCE -> allowances(scopedUsages, scopedSubscriptions);
            default -> Stream.concat(Stream.concat(
                    scopedUsages.stream().map(usage -> usage.usage().dataUsedMb()),
                    scopedUsages.stream().map(usage -> usage.usage().remainingDataMb())),
                    allowances(scopedUsages, scopedSubscriptions));
        };
        return values.filter(Objects::nonNull).toList();
    }

    private static Stream<Long> allowances(List<Usage> usages, List<SubscriptionSummary> subscriptions) {
        return Stream.concat(
                usages.stream().map(usage -> usage.usage().allowance() == null ? null : usage.usage().allowance().dataAmountMb()),
                subscriptions.stream().flatMap(subscription -> Stream.concat(
                        Stream.of(subscription.plan() == null ? null : subscription.plan().summary().dataAmountMb()),
                        subscription.roamings().stream().map(roaming -> roaming.dataAmountMb()))));
    }

    /** "12.3GB 사용하셨고"처럼 값 뒤에 오는 말을 먼저 보고, 없으면 값 앞(이전 값 뒤부터)의 말을 봅니다. */
    private DataItem dataItem(String sentence, int start, int end, int previousEnd) {
        String after = sentence.substring(end, Math.min(sentence.length(), end + 8));
        DataItem found = closest(after, true);
        if (found != null) return found;
        found = closest(sentence.substring(Math.max(previousEnd, start - KEYWORD_WINDOW), start), false);
        return found == null ? DataItem.ANY : found;
    }

    private DataItem closest(String window, boolean first) {
        DataItem found = null;
        int foundAt = first ? Integer.MAX_VALUE : -1;
        for (ItemKeyword keyword : DATA_ITEMS) {
            Matcher matcher = keyword.pattern().matcher(window);
            while (matcher.find()) {
                if (first ? matcher.start() < foundAt : matcher.start() > foundAt) {
                    foundAt = matcher.start();
                    found = (DataItem) keyword.item();
                }
            }
        }
        return found;
    }

    private static double toMegabytes(Matcher data) {
        double value = Double.parseDouble(data.group(1));
        String unit = data.group(2).toUpperCase();
        return unit.startsWith("M") || unit.startsWith("메") ? value : value * 1024;
    }

    // ---- 월·회선 ----

    private List<MonthRef> monthRefs(String sentence) {
        List<MonthRef> refs = new ArrayList<>();
        Matcher month = MONTH.matcher(sentence);
        while (month.find()) {
            Integer year = month.group(1) == null ? null : Integer.valueOf(month.group(1));
            String suffix = month.group(3) == null ? "" : month.group(3).strip();
            MonthKind kind = suffix.startsWith("사용") ? MonthKind.USAGE : suffix.startsWith("청구") ? MonthKind.BILL : MonthKind.PLAIN;
            refs.add(new MonthRef(month.start(), month.end(), kind,
                    new YearMonthMatch(year, Integer.parseInt(month.group(2)))));
        }
        addRelative(sentence, THIS_MONTH, MonthKind.THIS, refs);
        addRelative(sentence, LAST_MONTH, MonthKind.LAST, refs);
        refs.sort((left, right) -> Integer.compare(left.start(), right.start()));
        return refs;
    }

    private static void addRelative(String sentence, Pattern pattern, MonthKind kind, List<MonthRef> refs) {
        Matcher matcher = pattern.matcher(sentence);
        while (matcher.find()) refs.add(new MonthRef(matcher.start(), matcher.end(), kind, null));
    }

    /** 값 앞에 나온 마지막 월을 씁니다. 앞에 없으면 뒤에 나온 월을 모두 쓰고, 문장에 월이 없으면 월로 거르지 않습니다. */
    private static List<MonthRef> refsFor(List<MonthRef> refs, int start) {
        MonthRef before = null;
        for (MonthRef ref : refs) {
            if (ref.end() <= start) before = ref;
        }
        return before != null ? List.of(before) : refs;
    }

    /** 청구서가 나온 달로 바꿉니다. "9월 사용분"은 10월 청구서이고, 그냥 "9월 요금"은 9월 청구서와 10월 청구서(9월 사용분) 모두 될 수 있습니다. */
    private List<YearMonthMatch> billMonths(MonthRef ref) {
        return switch (ref.kind()) {
            case BILL -> List.of(ref.month());
            case USAGE -> List.of(ref.month().plusMonths(1));
            case PLAIN -> List.of(ref.month(), ref.month().plusMonths(1));
            case THIS -> latestMonths(bills.stream().filter(Bill::latest).map(bill -> bill.billing().billingMonth()).toList(),
                    bills.stream().map(bill -> bill.billing().billingMonth()).toList());
            case LAST -> {
                List<YearMonthMatch> latest = latestMonths(
                        bills.stream().filter(Bill::latest).map(bill -> bill.billing().billingMonth()).toList(),
                        bills.stream().map(bill -> bill.billing().billingMonth()).toList());
                yield Stream.concat(latest.stream(), latest.stream().map(month -> month.plusMonths(-1))).toList();
            }
        };
    }

    /** 사용한 달로 바꿉니다. "10월 청구서"의 사용량은 9월 사용량입니다. */
    private List<YearMonthMatch> usageMonths(MonthRef ref) {
        return switch (ref.kind()) {
            case BILL -> List.of(ref.month().plusMonths(-1));
            case USAGE, PLAIN -> List.of(ref.month());
            case THIS -> latestMonths(usages.stream().filter(Usage::latest).map(usage -> usage.usage().billingMonth()).toList(),
                    usages.stream().map(usage -> usage.usage().billingMonth()).toList());
            case LAST -> latestMonths(usages.stream().filter(Usage::latest).map(usage -> usage.usage().billingMonth()).toList(),
                    usages.stream().map(usage -> usage.usage().billingMonth()).toList())
                    .stream().map(month -> month.plusMonths(-1)).toList();
        };
    }

    /** 요약의 최근 기록 달입니다. 요약이 없으면 기록 중 가장 최근 달입니다. */
    private static List<YearMonthMatch> latestMonths(List<YearMonth> latest, List<YearMonth> all) {
        List<YearMonth> months = latest.isEmpty()
                ? all.stream().max(YearMonth::compareTo).stream().toList() : latest;
        return months.stream().map(month -> new YearMonthMatch(month.getYear(), month.getMonthValue())).distinct().toList();
    }

    private static boolean matchesAny(Set<YearMonthMatch> months, YearMonth month) {
        return month != null && months.stream().anyMatch(candidate -> candidate.matches(month));
    }

    /** 문장에 끝 4자리가 있고 그 번호의 기록이 있으면 그 회선 것만, 아니면 모두입니다. */
    private static <T> List<T> byPhone(String sentence, List<T> items, java.util.function.Function<T, String> phone) {
        Matcher matcher = PHONE.matcher(sentence);
        while (matcher.find()) {
            String last4 = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            Predicate<T> sameLine = item -> phone.apply(item).endsWith(last4);
            List<T> matched = items.stream().filter(sameLine).toList();
            if (!matched.isEmpty()) return matched;
        }
        return items;
    }

    // ---- 공통 ----

    private static Long wonValue(Matcher money) {
        String tenThousands = money.group(1);
        String rest = money.group(2);
        if (tenThousands == null && rest == null) return null;
        long value = tenThousands == null ? 0 : Long.parseLong(tenThousands) * 10_000;
        return rest == null ? value : value + Long.parseLong(rest.replace(",", ""));
    }

    private static boolean appearsIn(String token, List<String> sources) {
        String compact = token.replaceAll("\\s+", "");
        return sources.stream().anyMatch(source -> source != null && source.replaceAll("\\s+", "").contains(compact));
    }

    private static String mask(String sentence, List<int[]> spans) {
        StringBuilder masked = new StringBuilder(sentence);
        spans.stream().sorted((left, right) -> Integer.compare(right[0], left[0]))
                .forEach(span -> masked.replace(span[0], span[1], "□"));
        return masked.toString();
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private record Bill(String phone, BillingRecordResponseDto billing, boolean latest) {
    }

    private record Usage(String phone, UsageRecordResponseDto usage, boolean latest) {
    }

    private enum MonthKind { BILL, USAGE, PLAIN, THIS, LAST }

    private record MonthRef(int start, int end, MonthKind kind, YearMonthMatch month) {
    }

    /** 연도 없이 "9월"만 말하면 월만 비교합니다. 기록은 최근 12개월이라 월만으로도 겹치지 않습니다. */
    private record YearMonthMatch(Integer year, int month) {

        YearMonthMatch plusMonths(int months) {
            YearMonth shifted = YearMonth.of(year == null ? 2000 : year, month).plusMonths(months);
            return new YearMonthMatch(year == null ? null : shifted.getYear(), shifted.getMonthValue());
        }

        boolean matches(YearMonth value) {
            return value.getMonthValue() == month && (year == null || value.getYear() == year);
        }
    }

    private enum MoneyItem { TOTAL, BASE, ADDON, ROAMING, EXTRA, DISCOUNT, PAID, UNPAID, PLAN_FEE, INSTALLMENT, ANY }

    private enum DataItem { USED, REMAINING, ALLOWANCE, ANY }

    private record ItemKeyword(Enum<?> item, Pattern pattern) {
    }
}
