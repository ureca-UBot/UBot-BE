package com.ubot.prompt.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.format.FaqAnswerFormat.FaqAnswer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FaqAnswerFormatTest {

    @Test
    void 스키마는_answer_evidence_ids_status_순서로_세_키를_모두_요구한다() {
        Map<String, Object> schema = FaqAnswerFormat.schema();

        assertThat(List.copyOf(((Map<?, ?>) schema.get("properties")).keySet()))
                .isEqualTo(List.of("answer", "evidence_ids", "status"));
        assertThat(schema.get("required")).isEqualTo(List.of("answer", "evidence_ids", "status"));
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
    }

    @Test
    void JSON에서_answer와_근거와_상태를_꺼낸다() {
        FaqAnswer parsed = FaqAnswerFormat.parse(
                "{\"answer\":\" 선택약정은 12개월과 24개월이 있습니다. \",\"evidence_ids\":[\"112\", 106],\"status\":\"ANSWER\"}");

        assertThat(parsed.answer()).isEqualTo("선택약정은 12개월과 24개월이 있습니다.");
        assertThat(parsed.evidenceIds()).containsExactly("112", "106");
        assertThat(parsed.status()).isEqualTo("ANSWER");
    }

    @Test
    void 키_순서가_달라도_읽는다() {
        FaqAnswer parsed = FaqAnswerFormat.parse(
                "{\"evidence_ids\":[],\"status\":\"ABSTAIN\",\"answer\":\"확인할 수 없습니다.\"}");

        assertThat(parsed.answer()).isEqualTo("확인할 수 없습니다.");
        assertThat(parsed.evidenceIds()).isEmpty();
        assertThat(parsed.status()).isEqualTo("ABSTAIN");
    }

    @Test
    void 마크다운_코드_블록으로_감싼_JSON도_읽는다() {
        FaqAnswer parsed = FaqAnswerFormat.parse("```json\n{\"answer\":\"답변\",\"evidence_ids\":[],\"status\":\"ANSWER\"}\n```");

        assertThat(parsed.answer()).isEqualTo("답변");
    }

    @Test
    void 코드_블록_언어_태그가_대문자여도_읽는다() {
        FaqAnswer parsed = FaqAnswerFormat.parse("```JSON\n{\"answer\":\"답변\",\"evidence_ids\":[],\"status\":\"ANSWER\"}\n```");

        assertThat(parsed.answer()).isEqualTo("답변");
    }

    @Test
    void JSON이_아닌_일반_문장은_그대로_답변으로_쓴다() {
        FaqAnswer parsed = FaqAnswerFormat.parse("  위치를 알려 주시면 가까운 매장을 찾아 드릴게요.  ");

        assertThat(parsed.answer()).isEqualTo("위치를 알려 주시면 가까운 매장을 찾아 드릴게요.");
        assertThat(parsed.evidenceIds()).isEmpty();
        assertThat(parsed.status()).isNull();
    }

    @Test
    void 목록에_없는_status와_배열이_아닌_근거는_버린다() {
        FaqAnswer parsed = FaqAnswerFormat.parse("{\"answer\":\"답변\",\"evidence_ids\":\"112\",\"status\":\"CLARIFY\"}");

        assertThat(parsed.answer()).isEqualTo("답변");
        assertThat(parsed.evidenceIds()).isEmpty();
        assertThat(parsed.status()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"answer\": \"잘린 답",
            "{\"evidence_ids\":[],\"status\":\"ANSWER\"}",
            "{\"answer\":\"  \",\"evidence_ids\":[],\"status\":\"ANSWER\"}",
            "{\"answer\":[\"배열\"],\"evidence_ids\":[],\"status\":\"ANSWER\"}",
            "   "
    })
    void JSON을_읽을_수_없거나_answer가_없으면_LLM_응답_오류로_실패한다(String output) {
        assertThatThrownBy(() -> FaqAnswerFormat.parse(output))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
    }

    @Test
    void 칸_스키마는_칸_수만큼_answer_status_칸을_요구하고_PARTIAL은_없다() {
        Map<String, Object> schema = FaqAnswerFormat.slotSchema(2);

        assertThat(List.copyOf(((Map<?, ?>) schema.get("properties")).keySet())).isEqualTo(List.of("answers", "evidence_ids"));
        Map<?, ?> answers = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("answers");
        assertThat(answers.get("minItems")).isEqualTo(2);
        assertThat(answers.get("maxItems")).isEqualTo(2);
        Map<?, ?> slotProperties = (Map<?, ?>) ((Map<?, ?>) answers.get("items")).get("properties");
        assertThat(List.copyOf(slotProperties.keySet())).isEqualTo(List.of("answer", "status"));
        assertThat(((Map<?, ?>) slotProperties.get("status")).get("enum"))
                .isEqualTo(List.of("ANSWER", "ABSTAIN", "CONFLICT", "OUT_OF_SCOPE"));
    }

    @Test
    void 칸_형식은_칸별_answer와_status를_꺼내고_목록에_없는_status는_null로_둔다() {
        FaqAnswer parsed = FaqAnswerFormat.parse("{\"answers\":[{\"answer\":\" 매장에서 가능합니다. \",\"status\":\"ANSWER\"},"
                + "{\"status\":\"PARTIAL\"}],\"evidence_ids\":[\"12\"]}");

        assertThat(parsed.slots()).containsExactly(
                new FaqAnswerFormat.SlotAnswer("매장에서 가능합니다.", "ANSWER"),
                new FaqAnswerFormat.SlotAnswer("", null));
        assertThat(parsed.evidenceIds()).containsExactly("12");
        assertThat(parsed.answer()).isNull();
        assertThat(parsed.status()).isNull();
    }

    @Test
    void 기존_형식은_칸이_없다() {
        assertThat(FaqAnswerFormat.parse("{\"answer\":\"답변\",\"evidence_ids\":[],\"status\":\"ANSWER\"}").slots()).isNull();
    }
}
