package com.ubot.ai.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 상품 설명(description)을 도구 결과 행에 넣을 문장으로 다듬습니다. 상품별 가입 대상·포함 혜택·이용 조건 같은 규칙은
 * FAQ가 아니라 이 설명이 원본입니다.
 * 설명에는 "월 데이터 24GB", "월 3,300원"처럼 컬럼 값을 다시 쓴 구절이 섞여 있습니다. 수량은 컬럼이 원본이고 행에 이미 있으므로,
 * 수량이 든 구절(쉼표로 나눈 단위)은 뺍니다. 관리자가 컬럼만 고치고 설명을 그대로 두면 한 행에 값이 둘이 되는 것도 막습니다.
 */
final class ProductDescriptions {

    // 컬럼에 있는 수량(데이터·속도·금액·통화·문자)입니다. "만 19~34세", "1회선", "24시간"은 컬럼에 없는 규칙이라 남깁니다.
    private static final Pattern QUANTITY = Pattern.compile("\\d[\\d,.]*\\s*(?:TB|GB|MB|Gbps|Mbps|Kbps|kbps|원|분|건)");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=\\.)\\s+");
    private static final Pattern CLAUSE_BREAK = Pattern.compile(",\\s+");
    // 관리자는 무약정 요금제 설명에 이 구절을 넣습니다(예: "온라인 셀프개통 전용, 약정 없음.").
    private static final Pattern NO_CONTRACT = Pattern.compile("무약정|약정\\s*없음");

    private ProductDescriptions() {
    }

    /** 수량 구절을 뺀 문장입니다. 각 문장은 마침표로 끝나고, 남는 문장이 없으면 빈 목록입니다. */
    static List<String> sentences(String description) {
        List<String> sentences = new ArrayList<>();
        for (List<String> clauses : clausesBySentence(description)) {
            List<String> kept = clauses.stream().filter(clause -> !QUANTITY.matcher(clause).find()).toList();
            if (!kept.isEmpty()) {
                sentences.add(String.join(", ", kept) + ".");
            }
        }
        return sentences;
    }

    /** 설명에 "약정 없음" 구절이 있는 요금제입니다. 이런 요금제는 선택약정 할인을 받을 수 없습니다. */
    static boolean isNoContract(String description) {
        return clausesBySentence(description).stream().flatMap(List::stream)
                .anyMatch(clause -> NO_CONTRACT.matcher(clause).matches());
    }

    private static List<List<String>> clausesBySentence(String description) {
        if (!StringUtils.hasText(description)) {
            return List.of();
        }
        return Arrays.stream(SENTENCE_BREAK.split(description.strip()))
                .map(sentence -> sentence.strip().replaceAll("\\.$", ""))
                .filter(StringUtils::hasText)
                .map(sentence -> Arrays.stream(CLAUSE_BREAK.split(sentence)).map(String::strip)
                        .filter(StringUtils::hasText).toList())
                .toList();
    }
}
