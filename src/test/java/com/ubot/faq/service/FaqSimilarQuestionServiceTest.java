package com.ubot.faq.service;

import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FaqSimilarQuestionServiceTest {
    private final LlmService llmService = mock(LlmService.class);
    private final FaqSimilarQuestionService service = new FaqSimilarQuestionService(llmService);

    @Test
    void excludesOriginalAndDuplicatesAndLimitsToTen() {
        String original = "일시정지 중에도 요금이 발생하나요?";
        StringBuilder answer = new StringBuilder("1. " + original + "\n2. 일시정지 중 요금이 나오나요?\n"
                + "3. 일시정지 중   요금이 나오나요?\n");
        for (int number = 4; number <= 15; number++) {
            answer.append(number).append(". 표현 ").append(number).append("?\n");
        }
        when(llmService.generateAnswer(any(LlmRequestDto.class)))
                .thenReturn(new LlmResponseDto(answer.toString()));

        List<String> result = service.generate(original);

        assertThat(result).hasSize(10).doesNotContain(original);
        assertThat(result.getFirst()).isEqualTo("일시정지 중 요금이 나오나요?");
        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().messages().getFirst().content()).contains(original);
    }

    @Test
    void rejectsResponseWithoutUsableSimilarQuestion() {
        when(llmService.generateAnswer(any(LlmRequestDto.class)))
                .thenReturn(new LlmResponseDto("1. 원본 질문\n2. 원본 질문"));

        assertThatThrownBy(() -> service.generate("원본 질문"))
                .isInstanceOf(LlmException.class)
                .extracting(error -> ((LlmException) error).getErrorCode())
                .isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID);
    }
}
