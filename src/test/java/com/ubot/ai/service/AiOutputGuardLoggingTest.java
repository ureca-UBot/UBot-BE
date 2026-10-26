package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.exception.LlmException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AiOutputGuardLoggingTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(AiOutputGuardService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final AnswerMaterials materials = AnswerMaterials.builder("할인율 알려줘")
            .addFaqs(List.of(new FaqSearchResponseDto(1L, "할인율", "할인율은 25%입니다.", 0.9, Intent.GENERAL)))
            .build();
    private Level previousLevel;
    private AiOutputGuardService guard;

    @BeforeEach
    void setUp() {
        var repository = mock(ForbiddenWordRepository.class);
        when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(List.of(
                ForbiddenWord.builder().word("바보").status(ForbiddenWordStatus.ACTIVE).build()));
        var words = new ForbiddenWordFilterService(repository);
        words.refreshForbiddenWordCache();
        guard = new AiOutputGuardService(words);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }

    @Test
    void logsPassWithoutAnswerOrQuestionText() {
        String answer = "할인율은 25%입니다.";
        assertThat(guard.filterAnswer(answer, materials, null)).isEqualTo(answer);
        assertThat(messages()).singleElement().asString()
                .contains("단계=BODY", "결과=PASS", "사유=[]", "입력문자수=", "출력문자수=", "소요시간=")
                .contains("규칙별제거수={}", "제거단위수=0", "정리규칙={}", "거절사유=NONE")
                .doesNotContain(answer, materials.question());
    }

    @Test
    void logsPartialRemovalUsingExistingReasonWithoutExposingRemovedContent() {
        String answer = "할인율은 25%입니다. 바보야 고객비밀값-ABC123.";
        assertThatThrownBy(() -> guard.filterAnswer(answer, materials, null)).isInstanceOf(LlmException.class);
        assertThat(messages()).singleElement().asString()
                .contains("결과=REJECTED", "POLICY_VIOLATION", "FORBIDDEN_WORD=1", "제거단위수=1", "거절사유=WHOLE_ANSWER_REJECTION")
                .doesNotContain("바보", "고객비밀값-ABC123", "25%");
    }

    @Test
    void logsRejectedAnswerAndKeepsExistingException() {
        assertThatThrownBy(() -> guard.filterAnswer("바보야 고객비밀값-XYZ789", materials, null))
                .isInstanceOf(LlmException.class);
        assertThat(messages()).singleElement().asString()
                .contains("단계=BODY", "결과=REJECTED", "POLICY_VIOLATION", "출력문자수=0",
                        "FORBIDDEN_WORD=1", "제거단위수=1", "거절사유=NO_SAFE_CONTENT")
                .doesNotContain("고객비밀값-XYZ789", "바보");
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void logsEvidenceWarningWithoutRejectingTheAnswer() {
        guard.validateEvidenceIds(List.of("999999"), materials);
        assertThat(messages()).singleElement().asString()
                .contains("단계=EVIDENCE", "결과=WARNING", "사유=[]",
                        "경고규칙={UNPROVIDED_EVIDENCE_ID=1}", "제거단위수=0", "거절사유=NONE")
                .doesNotContain("999999");
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void identifiesQuantityCheckAndCountsOnlyRemovedSentences() {
        String answer = "할인율은 25%입니다. 할인율은 50%입니다.";
        assertThatThrownBy(() -> guard.filterAnswer(answer, materials, null)).isInstanceOf(LlmException.class);
        assertThat(messages()).singleElement().asString()
                .contains("결과=REJECTED", "QUANTITY_CONFLICT=1", "제거단위수=1", "거절사유=WHOLE_ANSWER_REJECTION")
                .doesNotContain("25%", "50%");
    }

    @Test
    void countsDifferentRemovedUnitsOnceEach() {
        String answer = "할인율은 25%입니다. 바보야. 할인율은 50%입니다.";
        assertThatThrownBy(() -> guard.filterAnswer(answer, materials, null)).isInstanceOf(LlmException.class);
        assertThat(messages()).singleElement().asString()
                .contains("FORBIDDEN_WORD=1", "QUANTITY_CONFLICT=1", "제거단위수=2");
    }

    @Test
    void separatesEmptyAnswerFromAllSentencesRemoved() {
        assertThatThrownBy(() -> guard.filterAnswer("  ", materials, null)).isInstanceOf(LlmException.class);
        assertThat(messages()).singleElement().asString()
                .contains("거절사유=EMPTY_ANSWER", "제거단위수=0", "규칙별제거수={}");
    }

    @Test
    void distinguishesMalformedEvidenceFromUnprovidedEvidence() {
        guard.validateEvidenceIds(List.of("customer-secret-ABC"), materials);
        assertThat(messages()).singleElement().asString()
                .contains("경고규칙={INVALID_EVIDENCE_ID=1}", "결과=WARNING", "거절사유=NONE", "제거단위수=0")
                .doesNotContain("customer-secret-ABC", "FORMAT_ERROR");
    }

    @Test
    void validOrMissingEvidenceKeepsExistingPolicyWithoutExtraLog() {
        guard.validateEvidenceIds(List.of("1"), materials);
        guard.validateEvidenceIds(List.of(), materials);
        guard.validateEvidenceIds(null, materials);
        assertThat(messages()).isEmpty();
    }

    @Test
    void inspectionEntryPointLogsExactlyOnceAndKeepsResultReasons() {
        var result = guard.inspectAnswer("할인율은 25%입니다. 바보야.", List.of("1"), materials, null);
        assertThat(result.rejected()).isTrue();
        assertThat(result.answer()).isNull();
        assertThat(result.reasons()).containsExactly(com.ubot.ai.dto.OutputGuardResult.Reason.POLICY_VIOLATION);
        assertThat(messages()).singleElement().asString()
                .contains("단계=BODY", "결과=REJECTED", "FORBIDDEN_WORD=1", "제거단위수=1");
    }

    @Test
    void inspectionEntryPointLogsMetadataWarningOnceAndPreservesCorrectBody() {
        var result = guard.inspectAnswer("할인율은 25%입니다.", List.of("999999"), materials, null);
        assertThat(result.rejected()).isFalse();
        assertThat(result.answer()).isEqualTo("할인율은 25%입니다.");
        assertThat(result.reasons()).isEmpty();
        assertThat(messages()).singleElement().asString()
                .contains("단계=BODY", "결과=PASS", "경고규칙={UNPROVIDED_EVIDENCE_ID=1}",
                        "거절사유=NONE", "제거단위수=0")
                .doesNotContain("999999", "25%");
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void countsMetadataWarningsWithoutLoggingValuesOrTreatingThemAsBodyFailures() {
        guard.validateEvidenceIds(java.util.Arrays.asList("1", "999999", null, " ", "bad-secret-id"), materials);
        assertThat(messages()).singleElement().asString()
                .contains("INVALID_EVIDENCE_ID=3", "UNPROVIDED_EVIDENCE_ID=1", "결과=WARNING",
                        "거절사유=NONE", "제거단위수=0", "사유=[]")
                .doesNotContain("999999", "bad-secret-id", "FORMAT_ERROR", "ANSWER_ERROR");
    }

    @Test
    void metadataWarningDoesNotHideActualBodyRejectionReason() {
        var result = guard.inspectAnswer("바보야.", List.of("999999"), materials, null);
        assertThat(result.rejected()).isTrue();
        assertThat(messages()).singleElement().asString()
                .contains("결과=REJECTED", "FORBIDDEN_WORD=1", "UNPROVIDED_EVIDENCE_ID=1",
                        "거절사유=NO_SAFE_CONTENT", "제거단위수=1")
                .doesNotContain("999999", "바보");
    }

    @Test
    void warningsDoNotLeakIntoTheNextCall() {
        guard.validateEvidenceIds(List.of("999999"), materials);
        guard.filterAnswer("할인율은 25%입니다.", materials, null);
        assertThat(messages()).hasSize(2);
        assertThat(messages().getLast()).contains("경고규칙={}", "결과=PASS", "거절사유=NONE");
        assertThat(appender.list.getLast().getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void eachCallStartsWithEmptyCountsAndRejectionReason() {
        guard.inspectAnswer("바보야.", materials, null);
        assertThat(guard.filterAnswer("할인율은 25%입니다.", materials, null)).isEqualTo("할인율은 25%입니다.");
        assertThat(messages()).hasSize(2);
        assertThat(messages().getLast())
                .contains("결과=PASS", "규칙별제거수={}", "제거단위수=0", "거절사유=NONE")
                .doesNotContain("FORBIDDEN_WORD", "NO_SAFE_CONTENT");
    }

    @Test
    void distinguishesDisplayReferenceCleanupFromUnsafeContentRemoval() {
        assertThat(guard.filterAnswer("[FAQ-1] 할인율은 25%입니다.", materials, null))
                .isEqualTo("할인율은 25%입니다.");
        assertThat(messages()).singleElement().asString()
                .contains("결과=FILTERED", "정리규칙={FAQ_REFERENCE=1}", "제거단위수=0", "거절사유=NONE");
    }

    @Test
    void logsNeverIncludeOriginalQuestionFaqAnswerOrExceptionPayload() {
        var source = AnswerMaterials.builder("할인율 질문-고객01012345678")
                .addFaqs(List.of(new FaqSearchResponseDto(77L, "근거질문-고객비밀",
                        "할인율은 25%입니다. 내부자료-비밀값.", 0.9, Intent.GENERAL))).build();
        guard.inspectAnswer("할인율은 25%입니다. 바보야 답변비밀값.", source, null);
        assertThat(messages()).singleElement().asString()
                .doesNotContain("01012345678", "근거질문", "내부자료", "비밀값", "바보", "25%");
        assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
