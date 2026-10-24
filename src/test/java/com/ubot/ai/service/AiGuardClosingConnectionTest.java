package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.config.AiOutputGuardProperties;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.service.PromptService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** 실제 출력 코드와 모의 생성·출력 LLM만 연결합니다. DB·외부 모델은 호출하지 않습니다. */
class AiGuardClosingConnectionTest {
    private static final String BODY = "요금제는 앱에서 변경할 수 있습니다.";
    private static final String CLOSING = "추가로 궁금한 사항이 있으면 알려주세요.";
    private final PromptService prompts = mock(PromptService.class);
    private final LlmService generation = mock(LlmService.class);
    private final AiLlmOutputGuardService llmGuard = mock(AiLlmOutputGuardService.class);
    private final AiToolRegistry tools = mock(AiToolRegistry.class);
    private final AiOutputGuardProperties properties = new AiOutputGuardProperties();
    private final ForbiddenWordFilterService words = new ForbiddenWordFilterService(mock(ForbiddenWordRepository.class))
            .preview(Set.of("바보"));
    private final AiService service = new AiService(prompts, generation, tools, new AiOutputGuardService(words),
            SubQuestionLabels.withoutMorphology(), llmGuard, properties);
    private final FaqSearchResponseDto faq = new FaqSearchResponseDto(1L, "요금제 변경 방법", BODY, .9, Intent.GENERAL);
    private final AnswerMaterials materials = AnswerMaterials.builder("요금제 변경 방법을 알려 주세요.")
            .addFaqs(List.of(faq)).subQuestions(List.of(new SubQuestionFaqs("요금제 변경 방법", List.of(faq), null))).build();

    @ParameterizedTest
    @ValueSource(strings = {"SLOT", "GENERAL", "CACHE"})
    void aClosingDoesNotDiscardTheAnswerAndTheLlmOnlyInspectsTheCleanedBody(String path) {
        var answer = answer(path, BODY + " " + CLOSING);
        assertThat(answer.answer()).isEqualTo(BODY);
        if (path.equals("CACHE")) {
            verifyNoInteractions(generation, prompts, llmGuard);
        } else {
            assertThat(answer.status()).isEqualTo("ANSWER");
            verify(llmGuard, times(1)).validate(eq(BODY), any(), any(), any());
            verify(generation, times(1)).generateAnswer(any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SLOT", "GENERAL", "CACHE"})
    void actualInformationRequestsStillRejectOrAbstainWithoutCallingTheOutputLlm(String path) {
        String body = BODY + " 정확한 요금제 이름을 알려주실 수 있나요?";
        if (path.equals("SLOT")) {
            assertThat(answer(path, body).status()).isEqualTo("ABSTAIN");
        } else {
            assertThatThrownBy(() -> answer(path, body)).isInstanceOfSatisfying(LlmException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
        }
        verifyNoInteractions(llmGuard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SLOT", "GENERAL", "CACHE"})
    void aClosingNeverExemptsAPolicyViolation(String path) {
        assertThatThrownBy(() -> answer(path, BODY + " 고객은 바보입니다. " + CLOSING))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
        verifyNoInteractions(llmGuard);
    }

    private com.ubot.ai.dto.AiAnswer answer(String path, String body) {
        if (path.equals("CACHE")) return service.validateCachedAnswer(materials, body, "1");
        when(tools.resolve(any())).thenReturn(List.of());
        when(prompts.createSlotPrompt(any(), any(), any(), any(), any())).thenReturn(new LlmRequestDto(List.of()));
        var json = JsonMapper.builder().build();
        Object response = path.equals("SLOT")
                ? Map.of("answers", List.of(Map.of("answer", body, "status", "ANSWER")), "evidence_ids", List.of("1"))
                : Map.of("answer", body, "status", "ANSWER", "evidence_ids", List.of("1"));
        when(generation.generateAnswer(any())).thenReturn(new LlmResponseDto(json.writeValueAsString(response)));
        return service.generateAnswer(materials);
    }
}
