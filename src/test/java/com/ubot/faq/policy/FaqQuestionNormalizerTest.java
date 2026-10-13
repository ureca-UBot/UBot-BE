package com.ubot.faq.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FaqQuestionNormalizerTest {
    @Test
    void collapsesOnlySpacingUnicodeCompositionAndRepeatedQuestionOrExclamationMarks() {
        Map<String, String> examples = Map.of(
                "  eSim이  뭔가요??  ", "eSim이 뭔가요?",
                "esim이\t뭐야？？？", "esim이 뭐야?",
                "esim이\u00A0\u3000뭐야！！!!", "esim이 뭐야!",
                Normalizer.normalize("이심이 뭐야?", Normalizer.Form.NFD), "이심이 뭐야?",
                "eSIM이 뭐야?!?", "eSIM이 뭐야?!?",
                "\u00A0\u3000\t ", "");
        examples.forEach((input, expected) -> {
            assertThat(FaqQuestionNormalizer.normalize(input)).isEqualTo(expected);
            assertThat(FaqQuestionNormalizer.normalize(expected)).isEqualTo(expected);
        });
    }

    @Test
    void keepsNegationConditionsCaseAndSeparateSentenceBoundaries() {
        for (String question : List.of(
                "번호이동 없이 통신사를 바꿀 수 있나요?",
                "번호이동을 안 해도 기존 번호를 유지할 수 있나요?",
                "새 전화번호로 가입하고 싶어요!",
                "eSIM이 뭐야? 어디서 발급받아?",
                "eSIM이 뭐야?\n발급 방법도 알려줘",
                "eSIM이 뭐야?\r\n발급 방법도 알려줘",
                "eSIM이 뭐야; 발급 방법도 알려줘",
                "eSIM이 뭐야...")) {
            assertThat(FaqQuestionNormalizer.normalize(question)).isEqualTo(question);
        }
        assertThat(FaqQuestionNormalizer.normalize("eSIM이 뭐야?? 어디서 발급받아??"))
                .isEqualTo("eSIM이 뭐야? 어디서 발급받아?");
        assertThat(FaqQuestionNormalizer.normalize(null)).isNull();
    }
}
