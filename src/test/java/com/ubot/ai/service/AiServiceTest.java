package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.exception.PromptException;
import com.ubot.prompt.service.PromptService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private PromptService promptService;

    @Mock
    private LlmService llmService;

    @InjectMocks
    private AiService aiService;

    @Test
    void 생성된_프롬프트를_LLM에_전달하고_응답을_반환한다() {
        var results = List.of(new FaqSearchResponseDto(1L, "FAQ 질문", "FAQ 답변", 0.9));
        var request = new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "구성된 입력")));
        when(promptService.createPrompt("사용자 질문", results)).thenReturn(request);
        when(llmService.generateAnswer(request)).thenReturn(new LlmResponseDto("생성 답변"));

        assertThat(aiService.generateAnswer("사용자 질문", results).answer()).isEqualTo("생성 답변");
        verify(promptService).createPrompt("사용자 질문", results);
        verify(llmService).generateAnswer(request);
    }

    @Test
    void 프롬프트_준비가_안_되면_LLM은_호출하지_않는다() {
        var results = List.of(new FaqSearchResponseDto(1L, "FAQ 질문", "FAQ 답변", 0.9));
        var failure = new PromptException("프롬프트 준비 중");
        when(promptService.createPrompt("질문", results)).thenThrow(failure);

        assertThatThrownBy(() -> aiService.generateAnswer("질문", results)).isSameAs(failure);
        verifyNoInteractions(llmService);
    }
}
