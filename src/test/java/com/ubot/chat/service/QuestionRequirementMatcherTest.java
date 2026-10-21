package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.ubot.chat.service.QuestionMorphology.Morph;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 형태소 입력을 고정해 비교 규칙만 확인합니다. 실제 Kiwi의 분석 정확도는 이 검사에 포함하지 않습니다. */
class QuestionRequirementMatcherTest {
    private final QuestionMorphology morphology = mock(QuestionMorphology.class);
    private final QuestionRequirementMatcher matcher = new QuestionRequirementMatcher(morphology);
    private Morph m(String form, String tag) { return new Morph(form, tag, 0, form.length(), form); }
    private List<Morph> terms(String subject, String number, String condition, String ending) {
        return List.of(m(subject, "NNG"), m(number, "SN"), m("개월", "NNB"), m(condition, "JX"),
                m("가입", "NNG"), m("되", "VV"), m(ending, "EF"), m("?", "SF"));
    }
    @Test void 종결_말투만_바뀌면_같은_요구다() {
        when(morphology.analyze("a")).thenReturn(terms("유심", "12", "만", "나요"));
        when(morphology.analyze("b")).thenReturn(terms("유심", "12", "만", "어요"));
        assertThat(matcher.signature("a")).isPresent().isEqualTo(matcher.signature("b"));
    }
    @Test void 대상_수치_조건은_서로_구분한다() {
        when(morphology.analyze("a")).thenReturn(terms("유심", "12", "만", "나요"));
        when(morphology.analyze("b")).thenReturn(terms("이심", "12", "만", "나요"));
        when(morphology.analyze("c")).thenReturn(terms("유심", "24", "만", "나요"));
        when(morphology.analyze("d")).thenReturn(terms("유심", "12", "도", "나요"));
        for (String other : List.of("b", "c", "d")) assertThat(matcher.signature("a")).isNotEqualTo(matcher.signature(other));
    }
    @Test void 부정과_행위가_다르면_다른_요구다() {
        when(morphology.analyze("a")).thenReturn(List.of(m("회선", "NNG"), m("가입", "NNG"), m("되", "VV"), m("나요", "EF")));
        when(morphology.analyze("b")).thenReturn(List.of(m("회선", "NNG"), m("가입", "NNG"), m("안", "MAG"), m("되", "VV"), m("나요", "EF")));
        when(morphology.analyze("c")).thenReturn(List.of(m("회선", "NNG"), m("해지", "NNG"), m("되", "VV"), m("나요", "EF")));
        assertThat(matcher.signature("a")).isNotEqualTo(matcher.signature("b")).isNotEqualTo(matcher.signature("c"));
    }
    @ParameterizedTest @ValueSource(strings = {"그거", "오늘", "해당"})
    void 맥락에_따라_뜻이_달라지면_자동_동일시하지_않는다(String word) {
        when(morphology.analyze(word)).thenReturn(List.of(m(word, "NNG"), m("가입", "NNG"), m("되", "VV"), m("나요", "EF")));
        assertThat(matcher.signature(word)).isEmpty();
    }
    @Test void 복합요청과_분석실패는_보수적으로_남긴다() {
        when(morphology.analyze("복합")).thenReturn(List.of(m("가입", "NNG"), m("고", "EC"), m("해지", "NNG")));
        when(morphology.analyze("실패")).thenThrow(new MorphologyException(new Exception("mock")));
        assertThat(matcher.signature("복합")).isEmpty();
        assertThat(matcher.signature("실패")).isEmpty();
    }
}
