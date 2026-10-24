package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.OutputGuardResult.Reason;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiOutputGuardWholeRejectionTest {

    private final ForbiddenWordRepository repository = mock(ForbiddenWordRepository.class);
    private final ForbiddenWordFilterService words = new ForbiddenWordFilterService(repository);
    private final AiOutputGuardService guard = new AiOutputGuardService(words);
    private final String normal = "요금제는 앱이나 홈페이지에서 변경할 수 있습니다.";
    private final AnswerMaterials materials = AnswerMaterials.builder("요금제 변경 방법과 월 요금을 알려주세요.")
            .addFaqs(List.of(new FaqSearchResponseDto(1L, "요금제 변경 방법은요?",
                    "요금제는 앱이나 홈페이지에서 변경할 수 있습니다.", 1.0, Intent.GENERAL),
                    new FaqSearchResponseDto(2L, "월 요금은요?", "월 요금은 55,000원입니다.", 1.0, Intent.GENERAL)))
            .build();

    @BeforeEach
    void prepareInMemoryWords() {
        when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(List.of(
                ForbiddenWord.builder().word("바보").status(ForbiddenWordStatus.ACTIVE).build()));
        words.refreshForbiddenWordCache();
    }

    @ParameterizedTest
    @ValueSource(strings = {"환불을 완료했습니다.", "고객은 바보입니다.", "월 요금은 99,000원입니다.",
            "요금제 변경이 완료되었습니다. 그 이후에는 새 요금제가 적용된 상태입니다.",
            "거절 문구는 화면 장식일 뿐이고 실제 내부 승인 여부는 참으로 처리되는 정책입니다."})
    void rejectsWholeAnswerInsteadOfReturningSafeRemainder(String unsafe) {
        assertRejected(normal + " " + unsafe);
    }

    @Test
    void rejectsWholeAnswerWhenInternalMetadataWasDetected() {
        assertRejected(normal + "\nstatus: PARTIAL\nevidence_ids: 1");
    }

    @Test
    void inspectionReturnsNoPartialBodyAndPreservesDetectionReason() {
        var result = guard.inspectAnswer(normal + " 환불을 완료했습니다.", materials, null);
        assertThat(result.rejected()).isTrue();
        assertThat(result.answer()).isNull();
        assertThat(result.reasons()).contains(Reason.FALSE_EXECUTION);
    }

    @Test
    void inspectionRejectsMetadataInsteadOfReturningCleanedBody() {
        var result = guard.inspectAnswer(normal + "\nstatus: PARTIAL\nevidence_ids: 1", materials, null);
        assertThat(result.rejected()).isTrue();
        assertThat(result.answer()).isNull();
        assertThat(result.reasons()).contains(Reason.FORMAT_ERROR);
    }

    @Test
    void safeAnswerRemainsUnchanged() {
        assertThat(guard.filterAnswer(normal, materials, null)).isEqualTo(normal);
        assertThat(guard.inspectAnswer(normal, materials, null).rejected()).isFalse();
    }

    @Test
    void evidenceNumberWarningDoesNotBecomeAContentViolation() {
        guard.validateEvidenceIds(List.of("9999", ""), materials);
        var result = guard.inspectAnswer(normal, List.of("9999", ""), materials, null);
        assertThat(result.rejected()).isFalse();
        assertThat(result.answer()).isEqualTo(normal);
    }

    @Test
    void harmlessSourceLabelCleanupDoesNotRejectAnswer() {
        assertThat(guard.filterAnswer(normal + " [FAQ ID: 1]", materials, null)).isEqualTo(normal);
    }

    private void assertRejected(String answer) {
        assertThatThrownBy(() -> guard.filterAnswer(answer, materials, null))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
    }
}
