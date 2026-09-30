package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.Location;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.StoreTools;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.service.LlmService;
import com.ubot.location.service.LocationService;
import com.ubot.prompt.exception.PromptException;
import com.ubot.prompt.service.PromptService;
import com.ubot.store.service.StoreService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private PromptService promptService;

    @Mock
    private LlmService llmService;

    private AiService aiService;

    private final List<FaqSearchResponseDto> faqs = List.of(new FaqSearchResponseDto(1L, "FAQ 질문", "FAQ 답변", 0.9));
    private final LlmRequestDto prompt = new LlmRequestDto(List.of(
            new LlmMessageRequestDto(LlmMessageRole.USER, "구성된 입력")));

    @BeforeEach
    void setUp() {
        var registry = new AiToolRegistry(new StoreTools(mock(StoreService.class), mock(LocationService.class)));
        aiService = new AiService(promptService, llmService, registry);
    }

    @Test
    void 생성된_프롬프트를_도구_없이_LLM에_전달하고_응답을_반환한다() {
        var section = new ContextSection("사용자 정보", "이름: 홍길동");
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).addSection(section).build();
        when(promptService.createPrompt("사용자 질문", faqs, List.of(section))).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto("생성 답변"));

        assertThat(aiService.generateAnswer(materials).answer()).isEqualTo("생성 답변");
        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().toolCallbacks()).isEmpty();
        assertThat(request.getValue().toolContext()).isEmpty();
    }

    @Test
    void 매장_도구가_켜지면_도구와_질문_위치를_toolContext로_전달한다() {
        var materials = AnswerMaterials.builder("근처 매장 알려줘")
                .enableTool(AiTool.STORE_SEARCH)
                .location(new Location(37.5, 127.0))
                .build();
        when(promptService.createPrompt("근처 매장 알려줘", List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));

        aiService.generateAnswer(materials);

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().messages()).isEqualTo(prompt.messages());
        assertThat(request.getValue().toolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name()).containsExactly("findNearbyStores");
        assertThat(request.getValue().toolContext()).isEqualTo(Map.of(
                StoreTools.QUESTION, "근처 매장 알려줘",
                StoreTools.LATITUDE, 37.5,
                StoreTools.LONGITUDE, 127.0));
    }

    @Test
    void 위치가_없어도_질문은_toolContext에_넣는다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createPrompt("근처 매장", List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));

        aiService.generateAnswer(materials);

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().toolContext()).isEqualTo(Map.of(StoreTools.QUESTION, "근처 매장"));
    }

    @Test
    void 자료가_하나도_없으면_프롬프트와_LLM을_호출하지_않는다() {
        assertThatThrownBy(() -> aiService.generateAnswer(AnswerMaterials.builder("질문").build()))
                .isInstanceOf(PromptException.class);
        verifyNoInteractions(promptService, llmService);
    }

    @Test
    void 프롬프트_준비가_안_되면_LLM은_호출하지_않는다() {
        var failure = new PromptException("프롬프트 준비 중");
        when(promptService.createPrompt("질문", faqs, List.of())).thenThrow(failure);

        assertThatThrownBy(() -> aiService.generateAnswer(AnswerMaterials.builder("질문").addFaqs(faqs).build()))
                .isSameAs(failure);
        verifyNoInteractions(llmService);
    }
}
