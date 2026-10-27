package com.ubot.ai.tool;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 질문에 나온 달을 찾습니다. "8월 9월", "8~10월", "최근 3개월"처럼 여러 달을 묻는 질문에서 모델이 한 달만 조회하거나
 * 한 달만 답하는 일이 있어, 가입 정보 도구(UserDataTools)가 나머지 달을 함께 조회하고 AiService가 달마다 답을 쓰는 데 씁니다.
 * 숫자만 있는 달은 기준 달에서 가장 가까운 지난 달(기준 달 포함)로 봅니다. 예: 기준이 2026-10이면 11월은 2025-11입니다.
 */
public final class QuestionMonths {

    // "8월", "2026년 9월". 뒤에 "사용"이 붙으면 청구 월과 다를 수 있어 따로 표시합니다. "8월 5일"은 날짜라 뺍니다.
    private static final Pattern MONTH = Pattern.compile(
            "(?:(\\d{4})\\s*년\\s*)?(?<!\\d)(1[0-2]|0?[1-9])\\s*월(?!\\s*\\d{1,2}\\s*일)(\\s*사용)?");
    // "8~10월", "8월부터 10월까지", "8-10월", "2025년 8월부터 10월까지", "2025년 11월~2026년 2월"
    private static final Pattern RANGE = Pattern.compile(
            "(?:(\\d{4})\\s*년\\s*)?(?<!\\d)(1[0-2]|0?[1-9])\\s*월?\\s*(?:~|-|부터)\\s*"
                    + "(?:(\\d{4})\\s*년\\s*)?(1[0-2]|0?[1-9])\\s*월");
    // "최근 3개월", "지난 두 달", "요즘 석 달"
    private static final Pattern RECENT = Pattern.compile(
            "(?:최근|지난|요즘)\\s*(\\d|한|두|세|석|네|넉)\\s*(?:개월|달)");
    private static final Map<String, Integer> COUNT_WORDS = Map.of(
            "한", 1, "두", 2, "세", 3, "석", 3, "네", 4, "넉", 4);
    // 계산이 필요한 질문은 달마다 나열하는 답으로 대신하지 않습니다.
    private static final Pattern COMPARISON = Pattern.compile("비교|차이|평균|합계|합쳐|총합|변화|늘었|줄었|더\\s*많|더\\s*적|얼마나\\s*(?:늘|줄)");

    private QuestionMonths() {
    }

    /**
     * 질문에 나온 달입니다(최대 limit개, 나온 순서). 범위와 최근 N개월은 그 달들을 모두 넣습니다.
     * usageMonthMentioned는 "9월 사용분"처럼 사용한 달로 말한 표현이 있는지입니다.
     */
    public static Found find(String question, YearMonth current, int limit) {
        if (!StringUtils.hasText(question)) {
            return new Found(List.of(), false);
        }
        Set<YearMonth> months = new LinkedHashSet<>();
        // 범위로 읽은 구간입니다. 아래에서 달 하나씩 다시 읽으면 연도 없는 끝 달("10월")을 따로 해석하게 되어 건너뜁니다.
        List<int[]> rangeSpans = new ArrayList<>();
        Matcher range = RANGE.matcher(question);
        while (range.find()) {
            Range found = rangeOf(range, current);
            if (found.start().isAfter(found.end())) {
                continue;
            }
            for (YearMonth month = found.start(); !month.isAfter(found.end()); month = month.plusMonths(1)) {
                months.add(month);
            }
            rangeSpans.add(new int[] {range.start(), range.end()});
        }
        Matcher recent = RECENT.matcher(question);
        if (recent.find()) {
            int count = recentCount(recent.group(1));
            for (int back = 0; back < count; back++) {
                months.add(current.minusMonths(back));
            }
        }
        boolean usageMonthMentioned = false;
        Matcher month = MONTH.matcher(question);
        while (month.find()) {
            usageMonthMentioned |= month.group(3) != null;
            int at = month.start();
            if (rangeSpans.stream().anyMatch(span -> at >= span[0] && at < span[1])) {
                continue;
            }
            months.add(resolve(month.group(1), Integer.parseInt(month.group(2)), current));
        }
        return new Found(new ArrayList<>(months).stream().limit(limit).toList(), usageMonthMentioned);
    }

    /** 여러 달을 묻는지입니다. 기준 달 없이 표현만 봅니다. */
    public static boolean asksMultipleMonths(String question) {
        return find(question, YearMonth.of(2000, 12), Integer.MAX_VALUE).months().size() >= 2;
    }

    /** "비교", "차이"처럼 달 사이 계산이 필요한 질문인지입니다. */
    public static boolean asksComparison(String question) {
        return StringUtils.hasText(question) && COMPARISON.matcher(question).find();
    }

    private static int recentCount(String word) {
        return Character.isDigit(word.charAt(0)) ? Integer.parseInt(word) : COUNT_WORDS.get(word);
    }

    /**
     * 범위의 처음과 끝 달입니다. 끝 달을 먼저 정하고(끝 연도, 없으면 시작 연도 기준, 둘 다 없으면 기준 달에서 가장 가까운 지난 달),
     * 시작 달은 끝 달보다 늦지 않은 가장 가까운 달로 정합니다. 그래서 "11월~2월"은 해를 넘겨 이어지고,
     * 기준이 2026-09일 때 "8~10월"은 2025-08~2025-10처럼 한 해 안에서 이어집니다.
     */
    private static Range rangeOf(Matcher range, YearMonth current) {
        String fromYear = range.group(1);
        int from = Integer.parseInt(range.group(2));
        String toYear = range.group(3);
        int to = Integer.parseInt(range.group(4));
        YearMonth end;
        if (toYear != null) {
            end = YearMonth.of(Integer.parseInt(toYear), to);
        } else if (fromYear != null) {
            int year = Integer.parseInt(fromYear);
            end = YearMonth.of(to >= from ? year : year + 1, to);
        } else {
            end = resolve(null, to, current);
        }
        if (fromYear != null) {
            return new Range(YearMonth.of(Integer.parseInt(fromYear), from), end);
        }
        YearMonth start = YearMonth.of(end.getYear(), from);
        return new Range(start.isAfter(end) ? start.minusYears(1) : start, end);
    }

    private record Range(YearMonth start, YearMonth end) {
    }

    private static YearMonth resolve(String year, int month, YearMonth current) {
        if (year != null) {
            return YearMonth.of(Integer.parseInt(year), month);
        }
        YearMonth found = YearMonth.of(current.getYear(), month);
        return found.isAfter(current) ? found.minusYears(1) : found;
    }

    /** 질문에 나온 달과, 사용한 달로 말한 표현이 있는지입니다. */
    public record Found(List<YearMonth> months, boolean usageMonthMentioned) {
    }
}
