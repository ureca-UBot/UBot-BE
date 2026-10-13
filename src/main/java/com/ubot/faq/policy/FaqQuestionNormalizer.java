package com.ubot.faq.policy;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** 등록·검색·저장에 사용하는 공통 규칙. 질문의 조건·부정·문장 경계를 유지하고 표기 차이만 정리합니다. */
public final class FaqQuestionNormalizer {
    public static final int VERSION = 1;
    private static final Pattern SPACES = Pattern.compile("[ \\t\u00A0\u3000]+");
    private static final Pattern QUESTIONS = Pattern.compile("[?？]+");
    private static final Pattern EXCLAMATIONS = Pattern.compile("[!！]+");
    private static final Pattern EDGE_SPACES = Pattern.compile("\\A +| +\\z");

    private FaqQuestionNormalizer() {
    }

    public static String normalize(String question) {
        if (question == null) return null;
        String normalized = Normalizer.normalize(question, Normalizer.Form.NFC);
        normalized = SPACES.matcher(normalized).replaceAll(" ");
        normalized = QUESTIONS.matcher(normalized).replaceAll("?");
        normalized = EXCLAMATIONS.matcher(normalized).replaceAll("!");
        return EDGE_SPACES.matcher(normalized).replaceAll("");
    }
}
