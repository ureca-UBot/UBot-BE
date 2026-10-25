package com.ubot.prompt.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.format.AiGuardFormat.Decision;
import com.ubot.prompt.format.AiOutputGuardAssessment.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** 모의 JSON만 사용하며 실제 모델의 위험 탐지 정확도를 검증하지 않습니다. */
class AiOutputGuardAssessmentTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ANSWER = "조회가 완료되었습니다. 사용량은 3GB입니다.";

    @Test
    void 빈_목록은_사유_없는_허용이고_필수_9개_검사는_없다() {
        var result = AiOutputGuardAssessment.parse("{\"findings\":[]}", ANSWER);
        assertThat(AiOutputGuardAssessment.CONTRACT_VERSION).isEqualTo("output-findings-v1");
        assertThat(result.decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.reasons()).isEmpty();
        assertThat(result.findings()).isEmpty();
        assertThat(result.explanation()).isEqualTo("가드 판정: 허용. 보고된 위반·불확실 항목이 없습니다.");
    }

    @ParameterizedTest
    @EnumSource(value = Type.class, names = "INSUFFICIENT_EVIDENCE", mode = EnumSource.Mode.EXCLUDE)
    void 확정_유형에서_차단과_사유를_함께_도출한다(Type type) {
        var result = AiOutputGuardAssessment.parse(response(finding("CONFIRMED", type.name(), "조회가 완료되었습니다.")), ANSWER);
        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.reasons()).containsExactly(type.name());
        assertThat(result.explanation()).contains(type.description());
    }

    @ParameterizedTest
    @EnumSource(Type.class)
    void 불확실_유형은_보류로_유지한다(Type type) {
        var result = AiOutputGuardAssessment.parse(response(finding("UNCERTAIN", type.name(), "사용량은 3GB입니다.")), ANSWER);
        assertThat(result.decision()).isEqualTo(Decision.REVIEW);
        assertThat(result.reasons()).containsExactly(type.name());
    }

    @Test
    void 확정과_불확실이_섞이면_확정_사유만으로_전체를_차단한다() {
        var result = AiOutputGuardAssessment.parse(response(
                finding("UNCERTAIN", "INSUFFICIENT_EVIDENCE", "사용량은 3GB입니다."),
                finding("CONFIRMED", "FALSE_EXECUTION", "조회가 완료되었습니다.")), ANSWER);
        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.reasons()).containsExactly("FALSE_EXECUTION");
        assertThat(result.findings()).hasSize(2);
        assertThatThrownBy(() -> result.reasons().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.findings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{}", "null", "[]", "{\"findings\":null}",
            "{\"checks\":{}}", "{\"findings\":[],\"decision\":\"ALLOW\"}",
            "{\"decision\":\"ALLOW\",\"reason_codes\":[\"FALSE_EXECUTION\"],\"explanation\":\"정상\"}",
            "{\"findings\":[],\"findings\":[]}", "{\"findings\":[]} {}", "{\"findings\":["})
    void 구형_응답이나_모순_필드를_변환하지_않고_실패한다(String raw) {
        invalid(raw, ANSWER);
    }

    @Test
    void 답변에_없는_인용과_공백_유형_위조를_허용하지_않는다() {
        invalid(response(finding("CONFIRMED", "FALSE_EXECUTION", "FAQ에만 있는 문장")), ANSWER);
        invalid(response(finding("CONFIRMED", "FALSE_EXECUTION", " ")), ANSWER);
        invalid(response(finding("CONFIRMED", "INSUFFICIENT_EVIDENCE", "조회")), ANSWER);
        invalid(response(finding("CONFIRMED", "SAFE", "조회")), ANSWER);
        invalid(response(finding("OTHER", "FALSE_EXECUTION", "조회")), ANSWER);
    }

    @Test
    void 인용은_원문의_240코드포인트까지_최대_4건이다() {
        String quote = "😀".repeat(240);
        assertThat(AiOutputGuardAssessment.parse(response(finding("UNCERTAIN", "INSUFFICIENT_EVIDENCE", quote)), quote)
                .decision()).isEqualTo(Decision.REVIEW);
        invalid(response(finding("UNCERTAIN", "INSUFFICIENT_EVIDENCE", quote + "😀")), quote + "😀");
        var item = finding("CONFIRMED", "FALSE_EXECUTION", "조회");
        String tooMany = JSON.writeValueAsString(Map.of("findings", Collections.nCopies(5, item)));
        invalid(tooMany, ANSWER);
    }

    @Test
    void 파서는_모델의_의미_판단을_임의로_뒤집지_않는다() {
        String safe = "현재 조회 결과를 확인할 수 없습니다.";
        var result = AiOutputGuardAssessment.parse(response(finding("CONFIRMED", "ABUSIVE_LANGUAGE", safe)), safe);
        assertThat(result.decision()).isEqualTo(Decision.BLOCK);
        assertThat(result.reasons()).containsExactly("ABUSIVE_LANGUAGE");
    }

    @SafeVarargs
    private static String response(Map<String, String>... findings) {
        return JSON.writeValueAsString(Map.of("findings", List.of(findings)));
    }

    private static Map<String, String> finding(String certainty, String type, String quote) {
        return Map.of("certainty", certainty, "type", type, "answer_quote", quote);
    }

    private void invalid(String raw, String answer) {
        assertThatThrownBy(() -> AiOutputGuardAssessment.parse(raw, answer))
                .isInstanceOfSatisfying(LlmException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID);
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getMessage()).doesNotContain(answer);
                });
    }
}
