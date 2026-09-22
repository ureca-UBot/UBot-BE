package com.ubot.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.ai.service.AiService;
import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.chat.service.ChatService;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.faq.dto.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.client.LlmClient;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.service.PromptService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Controller부터 프롬프트 구성과 LlmService까지 실제로 연결합니다.
// DB 검색과 모델 통신만 모의 객체를 사용하므로 외부 서버 없이 검증할 수 있습니다.
@WebMvcTest(controllers = ChatController.class, properties = {
        "prompt.faq.system-location=classpath:prompts/test-faq-system.txt",
        "prompt.faq.user-location=classpath:prompts/test-faq-user.txt"
})
@Import({ChatService.class, AiService.class, PromptService.class, LlmService.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class ChatLlmIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FaqVectorService faqVectorService;

    @MockitoBean
    private LlmClient llmClient;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void 채팅_질문과_FAQ가_LLM에_전달되고_생성_답변이_HTTP로_반환된다() throws Exception {
        when(faqVectorService.getSimilarList("유심 재발급", 3)).thenReturn(List.of(
                new FaqSearchResponseDto(12L, "재발급 방법", "매장 방문", 0.9),
                new FaqSearchResponseDto(13L, "준비물", "준비물 안내 원문", 0.8)));
        when(llmClient.generateAnswer(any(LlmRequestDto.class)))
                .thenReturn(new LlmResponseDto("모의 LLM이 생성한 답변"));

        mockMvc.perform(post("/chat/questions").contentType("application/json")
                        .content("{\"question\":\"유심 재발급\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.answer").value("모의 LLM이 생성한 답변"));

        var request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmClient).generateAnswer(request.capture());
        assertThat(request.getValue().messages().getLast().content())
                .contains("유심 재발급", "[FAQ ID: 12]", "[FAQ ID: 13]", "매장 방문", "준비물 안내 원문");
    }

    @Test
    void 유사도가_부족하면_LLM을_호출하지_않는다() throws Exception {
        when(faqVectorService.getSimilarList("질문", 3))
                .thenReturn(List.of(new FaqSearchResponseDto(1L, "FAQ 질문", "답변", 0.74)));

        mockMvc.perform(post("/chat/questions").contentType("application/json")
                        .content("{\"question\":\"질문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.answer").value("정확한 답변을 찾지 못했습니다."));
        verifyNoInteractions(llmClient);
    }

    @Test
    void 모델_시간초과를_채팅_실패_응답으로_전달한다() throws Exception {
        when(faqVectorService.getSimilarList("질문", 3))
                .thenReturn(List.of(new FaqSearchResponseDto(1L, "FAQ 질문", "답변", 0.9)));
        when(llmClient.generateAnswer(any(LlmRequestDto.class)))
                .thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));

        mockMvc.perform(post("/chat/questions").contentType("application/json")
                        .content("{\"question\":\"질문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.answer").value(LlmErrorCode.LLM_TIMEOUT.getMessage()));
    }
}
