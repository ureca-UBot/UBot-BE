package com.ubot.prompt.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("판정 LLM 출력 형식 테스트")
class SubQuestionFormatTest {

    @Test
    @DisplayName("하위 질문과 맥락 사용 여부를 꺼내고 앞뒤 공백과 빈 질문은 버린다")
    void parsesSubQuestions() {
        var output = SubQuestionFormat.parse("""
                {"context_used": true, "sub_questions": [" 5G 프리미엄 월 요금 ", "", "5G 프리미엄 데이터 제공량"]}
                """);

        assertThat(output.contextUsed()).isTrue();
        assertThat(output.subQuestions()).containsExactly("5G 프리미엄 월 요금", "5G 프리미엄 데이터 제공량");
    }

    @Test
    @DisplayName("코드 블록으로 감싼 JSON도 읽고, 맥락 사용 여부가 없으면 false로 본다")
    void parsesCodeBlockAndDefaultsContextUsed() {
        var output = SubQuestionFormat.parse("```json\n{\"sub_questions\": [\"유심 재발급\"]}\n```");

        assertThat(output.contextUsed()).isFalse();
        assertThat(output.subQuestions()).containsExactly("유심 재발급");
    }

    @Test
    @DisplayName("개수 제한 없이 모델이 적은 질문을 모두 돌려준다")
    void keepsEveryQuestion() {
        var output = SubQuestionFormat.parse("{\"context_used\": false, \"sub_questions\": [\"a\", \"b\", \"c\", \"d\"]}");

        assertThat(output.subQuestions()).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "질문을 정리했습니다.",
            "{\"context_used\": false",
            "{\"context_used\": false}",
            "{\"sub_questions\": \"유심 재발급\"}",
            "{\"sub_questions\": []}",
            "{\"sub_questions\": [\" \", 1]}"})
    @DisplayName("JSON이 아니거나 하위 질문이 없으면 LLM 응답 오류로 실패한다")
    void rejectsInvalidOutput(String output) {
        assertThatThrownBy(() -> SubQuestionFormat.parse(output))
                .isInstanceOfSatisfying(LlmException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
    }

    @Test
    @DisplayName("스키마는 context_used 다음 sub_questions 순서이고 개수 제한이 없다")
    void schemaHasNoItemLimit() {
        assertThat(SubQuestionFormat.schema().get("properties")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).containsOnlyKeys("context_used", "sub_questions");
        assertThat(SubQuestionFormat.schema().toString()).doesNotContain("maxItems");
    }
}
