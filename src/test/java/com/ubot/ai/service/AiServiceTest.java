package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.service.LlmService;
import com.ubot.location.service.LocationService;
import com.ubot.prompt.exception.PromptErrorCode;
import com.ubot.prompt.exception.PromptException;
import com.ubot.prompt.service.PromptService;
import com.ubot.store.service.StoreService;
import java.util.List;
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

    private final List<FaqSearchResponseDto> faqs = List.of(new FaqSearchResponseDto(1L, "FAQ 질문", "FAQ 답변", 0.9, Intent.GENERAL));
    private final LlmRequestDto prompt = new LlmRequestDto(List.of(
            new LlmMessageRequestDto(LlmMessageRole.USER, "구성된 입력")));

    @BeforeEach
    void setUp() {
        var registry = new AiToolRegistry(
                new StoreTools(new NearbyStoreSearcher(mock(StoreService.class), 3.0, 5), mock(LocationService.class)));
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
    void 매장_도구가_켜지면_도구와_질문_recorder를_toolContext로_전달한다() {
        var materials = AnswerMaterials.builder("근처 매장 알려줘").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createPrompt("근처 매장 알려줘", List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));

        aiService.generateAnswer(materials);

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().messages()).isEqualTo(prompt.messages());
        assertThat(request.getValue().toolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name()).containsExactly("findNearbyStores");
        assertThat(request.getValue().toolContext())
                .containsEntry(StoreTools.QUESTION, "근처 매장 알려줘")
                .hasEntrySatisfying(StoreTools.RECORDER,
                        recorder -> assertThat(recorder).isInstanceOf(StoreSearchRecorder.class))
                .hasSize(2);
    }

    @Test
    void 도구가_조회한_매장은_답변과_함께_반환한다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).build();
        var storeMap = new StoreMapResult(new Location(37.5, 127.0), null, 3.0, List.of());
        when(promptService.createPrompt("근처 매장", List.of(), List.of())).thenReturn(prompt);
        // 실제 도구 실행 대신, 도구가 요청의 recorder에 결과를 남기는 상황을 흉내 냅니다.
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            ((StoreSearchRecorder) request.toolContext().get(StoreTools.RECORDER)).record(storeMap);
            return new LlmResponseDto("근처 매장을 지도에 표시했어요.");
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo("근처 매장을 지도에 표시했어요.");
        assertThat(answer.storeMap()).isSameAs(storeMap);
    }

    @Test
    void 도구가_매장을_조회하지_않으면_지도_정보는_없다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createPrompt("근처 매장", List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("어느 지역인지 알려 주세요."));

        assertThat(aiService.generateAnswer(materials).storeMap()).isNull();
    }

    @Test
    void 도구가_위치_필요를_표시하면_locationRequired를_반환한다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createPrompt("근처 매장", List.of(), List.of())).thenReturn(prompt);
        // 도구가 기준 장소를 얻지 못해 recorder에 "위치 필요"를 남기는 상황을 흉내 냅니다.
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            ((StoreSearchRecorder) request.toolContext().get(StoreTools.RECORDER)).markLocationRequired();
            return new LlmResponseDto("위치를 알려 주세요.");
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.locationRequired()).isTrue();
        assertThat(answer.storeMap()).isNull();
    }

    @Test
    void 핸들러가_내_위치로_조회한_지도_정보를_도구_없이_반환한다() {
        var section = new ContextSection("현재 위치 근처 매장", "반경 3km 안에 매장이 없습니다.");
        var storeMap = new StoreMapResult(new Location(37.5, 127.0), null, 3.0, List.of());
        var materials = AnswerMaterials.builder("근처 매장").addSection(section).storeMap(storeMap).build();
        when(promptService.createPrompt("근처 매장", List.of(), List.of(section))).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto("근처에 매장이 없어요."));

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.storeMap()).isSameAs(storeMap);
        assertThat(answer.locationRequired()).isFalse();
    }

    @Test
    void 자료가_하나도_없으면_프롬프트와_LLM을_호출하지_않는다() {
        assertThatThrownBy(() -> aiService.generateAnswer(AnswerMaterials.builder("질문").build()))
                .isInstanceOfSatisfying(PromptException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(PromptErrorCode.PROMPT_INPUT_MISSING));
        verifyNoInteractions(promptService, llmService);
    }

    @Test
    void 프롬프트_준비가_안_되면_LLM은_호출하지_않는다() {
        var failure = new PromptException(PromptErrorCode.PROMPT_NOT_READY);
        when(promptService.createPrompt("질문", faqs, List.of())).thenThrow(failure);

        assertThatThrownBy(() -> aiService.generateAnswer(AnswerMaterials.builder("질문").addFaqs(faqs).build()))
                .isSameAs(failure);
        verifyNoInteractions(llmService);
    }
}
