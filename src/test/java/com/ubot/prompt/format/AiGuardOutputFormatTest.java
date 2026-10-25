package com.ubot.prompt.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiGuardOutputFormatTest {

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"SAFE\"]"})
    void acceptsExplicitAllowWithEmptyOrSafeReason(String reasons) {
        var verdict = AiGuardFormat.parseOutput("{\"decision\":\"ALLOW\",\"reason_codes\":" + reasons
                + ",\"explanation\":\"제공 근거에 맞는 정상 안내입니다.\"}");

        assertThat(verdict.decision()).isEqualTo(AiGuardFormat.Decision.ALLOW);
        assertThat(verdict.reasons()).isEqualTo("[]".equals(reasons) ? List.of() : List.of("SAFE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCK", "REVIEW"})
    void preservesBlockAndReviewAsSeparateDecisions(String decision) {
        var verdict = AiGuardFormat.parseOutput("{\"decision\":\"" + decision
                + "\",\"reason_codes\":[\"UNSUPPORTED_CLAIM\"],\"explanation\":\"확인이 필요합니다.\"}");

        assertThat(verdict.decision()).isEqualTo(AiGuardFormat.Decision.valueOf(decision));
        assertThat(verdict.reasons()).containsExactly("UNSUPPORTED_CLAIM");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"decision\":\"BLOCK\",\"reason_codes\":[],\"explanation\":\"사유 없음\"}",
            "{\"decision\":\"REVIEW\",\"reason_codes\":[],\"explanation\":\"사유 없음\"}",
            "{\"decision\":\"BLOCK\",\"reason_codes\":[\"SAFE\"],\"explanation\":\"모순\"}",
            "{\"decision\":\"REVIEW\",\"reason_codes\":[\"SAFE\"],\"explanation\":\"모순\"}",
            "{\"decision\":\"BLOCK\",\"reason_codes\":[\"SAFE\",\"SAFE\"],\"explanation\":\"모순\"}",
            "{\"decision\":\"ALLOW\",\"reason_codes\":[\"FALSE_EXECUTION\"],\"explanation\":\"모순\"}",
            "{\"decision\":\"ALLOW\",\"reason_codes\":[\"SAFE\",\"SAFE\"],\"explanation\":\"중복\"}",
            "{\"decision\":\"ALLOW\",\"reason_codes\":[\"UNKNOWN\"],\"explanation\":\"알 수 없음\"}",
            "{\"decision\":\"ALLOW\",\"reason_codes\":[]}",
            "{\"decision\":\"ALLOW\",\"reason_codes\":[],\"explanation\":null}",
            "{\"decision\":\"ALLOW\",\"reason_codes\":null,\"explanation\":\"누락\"}",
            "{\"decision\":\"ALLOW\",\"reason_codes\":[\"\"],\"explanation\":\"공백 사유\"}",
            "{\"decision\":\"ALLOW\",\"decision\":\"BLOCK\",\"reason_codes\":[],\"explanation\":\"중복 필드\"}",
            "{\"decision\":\"ALLOW\",\"reason_codes\":[],\"explanation\":\"안전\"} {}",
            "{\"decision\":\"ALLOW\",\"reason_codes\":[],\"explanation\":\"안전\",\"answer\":\"다시 작성\"}",
            "{\"decision\":\"ALLOW\",\"reason_codes\":[]",
            "not json"
    })
    void neverConvertsContradictoryOrMalformedOutputToAllow(String response) {
        assertThatThrownBy(() -> AiGuardFormat.parseOutput(response))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
    }

    @Test
    void stillRejectsExplanationBeyondTheSchemaLimit() {
        assertThatThrownBy(() -> AiGuardFormat.parseOutput("{\"decision\":\"ALLOW\",\"reason_codes\":[],"
                + "\"explanation\":\"" + "가".repeat(241) + "\"}"))
                .isInstanceOf(LlmException.class);
    }
}
