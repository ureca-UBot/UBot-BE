package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.exception.LlmException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("출력 가드 지표 테스트")
class AiOutputGuardMetricsTest {
    private final AnswerMaterials materials = AnswerMaterials.builder("할인율 알려줘")
            .addFaqs(List.of(new FaqSearchResponseDto(1L, "할인율", "할인율은 25%입니다.", 0.9, Intent.GENERAL)))
            .build();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private AiOutputGuardService guard;

    @BeforeEach
    void setUp() {
        var repository = mock(ForbiddenWordRepository.class);
        when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(List.of(
                ForbiddenWord.builder().word("바보").status(ForbiddenWordStatus.ACTIVE).build()));
        var words = new ForbiddenWordFilterService(repository);
        words.refreshForbiddenWordCache();
        guard = new AiOutputGuardService(words, registry);
    }

    @Test
    @DisplayName("통과·일부 제거·거절을 단계와 결과로 나눠 세고, 거절만 규칙을 남긴다")
    void countsBodyInspectionsByOutcome() {
        guard.filterAnswer("할인율은 25%입니다.", materials, null);
        guard.filterAnswer("할인율은 25%입니다. 바보야.", materials, null);
        assertThatThrownBy(() -> guard.filterAnswer("바보야 고객비밀값-XYZ789", materials, null))
                .isInstanceOf(LlmException.class);

        assertThat(inspections("body", "pass", "none")).isEqualTo(1);
        assertThat(inspections("body", "filtered", "none")).isEqualTo(1);
        assertThat(inspections("body", "rejected", "no_safe_content")).isEqualTo(1);
    }

    @Test
    @DisplayName("근거 번호 경고는 evidence 단계로 따로 센다")
    void countsEvidenceWarningsSeparately() {
        guard.validateEvidenceIds(List.of("999999"), materials);

        assertThat(inspections("evidence", "warning", "none")).isEqualTo(1);
        assertThat(registry.find("ai.output.guard").tag("stage", "body").counter()).isNull();
    }

    private double inspections(String stage, String outcome, String rule) {
        return registry.get("ai.output.guard").tag("stage", stage).tag("outcome", outcome).tag("rule", rule)
                .counter().count();
    }
}
