package com.ubot.llm.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.prompt.format.FaqAnswerFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResponseSchemaValidatorTest {
    private static final String VALID = "{\"answers\":[{\"answer\":\"\",\"status\":\"ABSTAIN\"}],\"evidence_ids\":[]}";

    @Test
    void acceptsEmptyAbstainAndCodeFencesWithoutChangingGenerationOrder() {
        assertThat(ResponseSchemaValidator.violation(VALID, FaqAnswerFormat.slotSchema(1))).isNull();
        assertThat(ResponseSchemaValidator.violation("```JSON\n" + VALID + "\n```", FaqAnswerFormat.slotSchema(1))).isNull();
    }

    @Test
    void usesActualRequestedSlotsNotSearchQuestionCount() {
        assertThat(ResponseSchemaValidator.violation(VALID, FaqAnswerFormat.slotSchema(1))).isNull();
        assertThat(ResponseSchemaValidator.violation(VALID, FaqAnswerFormat.slotSchema(2))).contains("too-few-items");
        String two = "{\"answers\":[{\"answer\":\"\",\"status\":\"ABSTAIN\"},{\"answer\":\"\",\"status\":\"ABSTAIN\"}],\"evidence_ids\":[]}";
        assertThat(ResponseSchemaValidator.violation(two, FaqAnswerFormat.slotSchema(1))).contains("too-many-items");
    }

    @Test
    void acceptsNumericEvidenceIdsLikeAnswerParsing() {
        String numeric = "{\"answers\":[{\"answer\":\"답\",\"status\":\"ANSWER\"}],\"evidence_ids\":[12,\"13\"]}";
        assertThat(ResponseSchemaValidator.violation(numeric, FaqAnswerFormat.slotSchema(1))).isNull();
        assertThat(ResponseSchemaValidator.violation("{\"answer\":\"답\",\"evidence_ids\":[12],\"status\":\"ANSWER\"}",
                FaqAnswerFormat.schema())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"answers\":[{\"answer\":\"답\",\"status\":\"ANSWER\"}],\"evidence_ids\":[null]}",
        "{\"answers\":[{\"answer\":\"답\",\"status\":\"ANSWER\"}],\"evidence_ids\":[{}]}",
        "{\"answers\":[{\"answer\":12,\"status\":\"ANSWER\"}],\"evidence_ids\":[]}"
    })
    void numericAllowanceAppliesOnlyToEvidenceIdNumbers(String text) {
        assertThat(ResponseSchemaValidator.violation(text, FaqAnswerFormat.slotSchema(1))).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "[]", "{}", "{\"answers\":null,\"evidence_ids\":[]}",
        "{\"answers\":[{\"status\":\"ANSWER\"}],\"evidence_ids\":[]}",
        "{\"answers\":[{\"answer\":null,\"status\":\"ANSWER\"}],\"evidence_ids\":[]}",
        "{\"answers\":[{\"answer\":\"\",\"status\":null}],\"evidence_ids\":[]}",
        "{\"answers\":[{\"answer\":\"\",\"status\":\"UNKNOWN\"}],\"evidence_ids\":[]}",
        "{\"answers\":[{\"answer\":\"\",\"status\":\"ANSWER\",\"other\":1}],\"evidence_ids\":[]}",
        "{\"answers\":[{\"answer\":\"\",\"status\":\"ANSWER\"}],\"evidence_ids\":[],\"extra\":1}"
    })
    void rejectsTypesMissingPropertiesAndUnknownKeys(String text) {
        assertThat(ResponseSchemaValidator.violation(text, FaqAnswerFormat.slotSchema(1))).isNotNull();
    }

    @Test
    void rejectsTrailingJsonAndSingleStatusSchemaMismatch() {
        assertThat(ResponseSchemaValidator.violation(VALID + " {}", FaqAnswerFormat.slotSchema(1))).isNotNull();
        assertThat(ResponseSchemaValidator.violation(VALID, FaqAnswerFormat.schema())).isNotNull();
        assertThat(ResponseSchemaValidator.violation("{\"answer\":\"답\",\"evidence_ids\":[],\"status\":\"ANSWER\"}",
                FaqAnswerFormat.schema())).isNull();
    }
}
