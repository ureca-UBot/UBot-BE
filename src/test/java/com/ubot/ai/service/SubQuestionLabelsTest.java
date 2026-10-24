package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import kr.pe.bab2min.Kiwi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SubQuestionLabelsTest {
    private static Kiwi kiwi;
    private static SubQuestionLabels labels;

    @BeforeAll
    static void loadKiwi() throws Exception {
        kiwi = Kiwi.init(System.getenv().getOrDefault("KIWI_MODEL_PATH", ".kiwi/model"));
        labels = new SubQuestionLabels(kiwi);
    }

    @AfterAll
    static void closeKiwi() throws Exception {
        kiwi.close();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "요금은 얼마고|요금은 얼마인지",
            "장기 일시정지는 최대 몇 년까지 되고|장기 일시정지는 최대 몇 년까지 되는지",
            "김치찌개 끓이는 법이랑|김치찌개 끓이는 법",
            "유심 재발급은 어떻게 하고|유심 재발급은 어떻게 하는지",
            "해외에서 쓸 수 있고|해외에서 쓸 수 있는지",
            "데이터가 비싸고|데이터가 비싼지",
            "가입은 했고,|가입은 했는지",
            "약정 기간이며|약정 기간인지",
            "결과랑|결과",
            "연장은 몇 번까지 돼요?|연장은 몇 번까지 돼요",
            "로밍 요금|로밍 요금"})
    void 원문_조각_끝의_접속조사는_떼고_연결어미는_는지로_바꾼다(String piece, String expected) {
        assertThat(labels.label(piece)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"요금은 얼마고?|요금은 얼마고", "로밍 요금.|로밍 요금"})
    void 형태소_분석이_없으면_끝의_문장_부호만_지운다(String piece, String expected) {
        assertThat(SubQuestionLabels.withoutMorphology().label(piece)).isEqualTo(expected);
    }
}
