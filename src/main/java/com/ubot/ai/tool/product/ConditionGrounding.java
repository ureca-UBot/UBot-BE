package com.ubot.ai.tool.product;

import com.ubot.bundle.enums.BundleType;
import com.ubot.plan.enums.NetworkType;
import com.ubot.plan.enums.PlanTargetGroup;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 모델이 넣은 조회 조건 중 사용자가 말한 조건인지 질문으로 확인합니다.
 * 작은 모델은 "밸런스 요금제 알려줘"에도 프롬프트에 있는 사용자 요금제 값(5G, 월 129,000원, 데이터 무제한)이나
 * 상품 이름의 숫자("프리미엄 89" → 월 89,000원 이하)를 조건으로 채워 0건을 만듭니다.
 * 상품 도구는 질문에 근거 말이 없는 조건을 빼고 조회합니다. 근거 문장은 이번 질문과 이전 사용자 질문입니다(ProductDataRecorder).
 */
final class ConditionGrounding {

    private static final Pattern FIVE_G = Pattern.compile("5\\s*G|5\\s*지|오지", Pattern.CASE_INSENSITIVE);
    private static final Pattern LTE = Pattern.compile("LTE|엘티이|4\\s*G", Pattern.CASE_INSENSITIVE);
    private static final Pattern YOUTH = Pattern.compile("청년|대학생|사회\\s*초년생|20대");
    private static final Pattern SENIOR = Pattern.compile("시니어|어르신|노인|실버|65세|부모님");
    private static final Pattern KIDS = Pattern.compile("키즈|어린이|아동|유아|초등|자녀|아이(?!폰|패드)");
    private static final Pattern WATCH = Pattern.compile("워치");
    private static final Pattern TABLET = Pattern.compile("태블릿|패드");
    // 금액: "5만원", "10만 이하", "39,000원", "3천원"
    private static final Pattern MONEY = Pattern.compile("\\d[\\d,.]*\\s*(?:만\\s*원?|천\\s*원|원)");
    // 데이터량: "10GB", "100기가", "500MB". "5G"(네트워크)와 섞이지 않게 G 하나는 두 자리 이상 숫자에만 봅니다.
    private static final Pattern DATA_AMOUNT = Pattern.compile(
            "\\d[\\d,.]*\\s*(?:GB|기가|MB|메가|테라)|(?<![\\d.])\\d{2,}\\s*G(?![A-Za-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNLIMITED = Pattern.compile("무제한|무한");
    private static final Pattern FAMILY = Pattern.compile("가족|식구");
    private static final Pattern HOME = Pattern.compile("홈|인터넷|집|와이파이|IPTV|TV|티비", Pattern.CASE_INSENSITIVE);
    private static final Pattern DUO = Pattern.compile("듀오|부모님|자녀|아이(?!폰|패드)");
    private static final Pattern ETC = Pattern.compile("친구|워치|태블릿");
    // 회선 수: "3인", "4명", "2회선", "세 명"
    private static final Pattern LINE_COUNT = Pattern.compile(
            "\\d+\\s*(?:인|명|회선)|(?:두|세|네|다섯|둘|셋|넷)\\s*(?:명|회선|사람|식구)");

    private ConditionGrounding() {
    }

    static boolean mentionsNetwork(String text, NetworkType type) {
        return switch (type) {
            case FIVE_G -> find(FIVE_G, text);
            case LTE -> find(LTE, text);
        };
    }

    /** 일반(GENERAL)은 사용자가 따로 말하는 대상이 아니라 늘 근거가 없는 것으로 봅니다. */
    static boolean mentionsTargetGroup(String text, PlanTargetGroup group) {
        return switch (group) {
            case GENERAL -> false;
            case YOUTH -> find(YOUTH, text);
            case SENIOR -> find(SENIOR, text);
            case KIDS -> find(KIDS, text);
            case WATCH -> find(WATCH, text);
            case TABLET -> find(TABLET, text);
        };
    }

    static boolean mentionsMoney(String text) {
        return find(MONEY, text);
    }

    static boolean mentionsDataAmount(String text) {
        return find(DATA_AMOUNT, text);
    }

    static boolean mentionsUnlimited(String text) {
        return find(UNLIMITED, text);
    }

    static boolean mentionsBundleType(String text, BundleType type) {
        return switch (type) {
            case FAMILY -> find(FAMILY, text);
            case HOME -> find(HOME, text);
            case DUO -> find(DUO, text);
            case ETC -> find(ETC, text);
        };
    }

    static boolean mentionsLineCount(String text) {
        return find(LINE_COUNT, text);
    }

    private static boolean find(Pattern pattern, String text) {
        return StringUtils.hasText(text) && pattern.matcher(text).find();
    }
}
