package com.ubot.ai.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.springframework.util.StringUtils;

/**
 * 상품 이름이 질문·답변에 나왔는지 찾습니다.
 * DB 이름은 모두 "Ubot 컬러링"처럼 접두어가 붙어 있지만 사용자와 모델은 "컬러링"이라고 쓰므로 접두어와 공백을 떼고 비교합니다.
 * "북패스"(부가서비스)가 "북패스 7GB+"(요금제) 안에 들어 있는 것처럼 짧은 이름이 긴 이름의 일부일 수 있어,
 * 긴 이름부터 찾고 이미 찾은 구간과 겹치는 짧은 이름은 일치로 세지 않습니다.
 */
public final class ProductNames {

    private static final String BRAND_PREFIX = "Ubot";
    // 한 글자 이름은 다른 말에 섞여 잘못 걸리므로 비교하지 않습니다.
    private static final int MIN_NAME_LENGTH = 2;

    private ProductNames() {
    }

    /** "Ubot 컬러링" → "컬러링". 공백도 모두 뗍니다. */
    public static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = value.strip();
        if (text.startsWith(BRAND_PREFIX)) {
            text = text.substring(BRAND_PREFIX.length());
        }
        return text.replaceAll("\\s+", "");
    }

    /** text에 이름이 나온 항목을 긴 이름 우선으로 찾습니다. 같은 이름의 항목이 여럿이면 모두 돌려줍니다. */
    public static <T> List<T> findMentioned(String text, List<T> items, Function<T, String> nameOf) {
        String target = normalize(text);
        if (target.isEmpty() || items.isEmpty()) {
            return List.of();
        }
        List<T> sorted = items.stream()
                .filter(item -> normalize(nameOf.apply(item)).length() >= MIN_NAME_LENGTH)
                .sorted(Comparator.comparingInt((T item) -> normalize(nameOf.apply(item)).length()).reversed())
                .toList();
        boolean[] covered = new boolean[target.length()];
        List<T> found = new ArrayList<>();
        for (T item : sorted) {
            String name = normalize(nameOf.apply(item));
            boolean mentioned = false;
            for (int start = target.indexOf(name); start >= 0; start = target.indexOf(name, start + 1)) {
                if (!overlaps(covered, start, name.length())) {
                    mark(covered, start, name.length());
                    mentioned = true;
                }
            }
            // 같은 이름을 다른 항목이 이미 표시했으면(이름이 같은 상품) 그 항목도 함께 일치로 봅니다.
            if (mentioned || found.stream().anyMatch(other -> normalize(nameOf.apply(other)).equals(name))) {
                found.add(item);
            }
        }
        return found;
    }

    private static boolean overlaps(boolean[] covered, int start, int length) {
        for (int index = start; index < start + length; index++) {
            if (covered[index]) {
                return true;
            }
        }
        return false;
    }

    private static void mark(boolean[] covered, int start, int length) {
        for (int index = start; index < start + length; index++) {
            covered[index] = true;
        }
    }
}
