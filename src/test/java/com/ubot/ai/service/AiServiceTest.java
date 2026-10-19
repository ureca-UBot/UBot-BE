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
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
        var words = mock(ForbiddenWordRepository.class);
        when(words.findAllByStatus(ForbiddenWordStatus.ACTIVE))
                .thenReturn(List.of(ForbiddenWord.builder().word("바보").status(ForbiddenWordStatus.ACTIVE).build()));
        var wordFilter = new ForbiddenWordFilterService(words);
        wordFilter.refreshForbiddenWordCache();
        aiService = new AiService(promptService, llmService, registry, new AiOutputGuardService(wordFilter));
    }

    @Test
    void 생성된_프롬프트를_도구_없이_LLM에_전달하고_응답을_반환한다() {
        var section = new ContextSection("사용자 정보", "이름: 홍길동");
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).addSection(section).build();
        when(promptService.createPrompt("사용자 질문", faqs, List.of(), List.of(section), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto("생성 답변"));

        assertThat(aiService.generateAnswer(materials).answer()).isEqualTo("생성 답변");
        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().toolCallbacks()).isEmpty();
        assertThat(request.getValue().toolContext()).isEmpty();
    }

    @Test
    void 모델이_낸_JSON에서_answer만_꺼내_반환한다() {
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).build();
        when(promptService.createPrompt("사용자 질문", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(
                "{\"answer\":\"선택약정은 12개월과 24개월이 있습니다.\",\"evidence_ids\":[\"1\"],\"status\":\"ANSWER\"}"));

        assertThat(aiService.generateAnswer(materials).answer()).isEqualTo("선택약정은 12개월과 24개월이 있습니다.");
    }

    @Test
    void 모델이_낸_JSON을_읽을_수_없으면_LLM_응답_오류로_실패한다() {
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).build();
        when(promptService.createPrompt("사용자 질문", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto("{\"answer\": \"잘린 답"));

        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"112", "FAQ-999999", "999999"})
    void 파서가_읽은_없는_근거를_출력_가드에서_거절한다(String id) {
        var materials = AnswerMaterials.builder("서류 안내").addFaqs(faqs).build();
        when(promptService.createPrompt("서류 안내", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(
                "{\"answer\":\"신분증을 지참해주세요.\",\"evidence_ids\":[\"" + id + "\"],\"status\":\"ANSWER\"}"));
        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
    }

    @Test
    void 매장_도구가_켜지면_도구와_질문_recorder를_toolContext로_전달한다() {
        var materials = AnswerMaterials.builder("근처 매장 알려줘").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createPrompt("근처 매장 알려줘", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
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
    void 이전_대화가_있으면_사용자_질문과_답변을_장소_검증용_질문에_함께_넣는다() {
        var history = List.of(new ConversationTurn("강남역 매장 알려줘", "강남역점이 있습니다."));
        var materials = AnswerMaterials.builder("거기 영업시간은?").enableTool(AiTool.STORE_SEARCH).build()
                .withHistory(history);
        when(promptService.createPrompt("거기 영업시간은?", List.of(), List.of(), List.of(), history)).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));

        aiService.generateAnswer(materials);

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        // 장소(강남역)가 이전 대화에만 있어도 도구의 "질문에 있는 장소인지" 검사를 통과하게 합니다. 이전 검색어는 넣지 않습니다.
        assertThat(request.getValue().toolContext())
                .containsEntry(StoreTools.QUESTION, "거기 영업시간은?\n강남역 매장 알려줘\n강남역점이 있습니다.");
    }

    @Test
    void 판정_LLM이_다시_쓴_검색어는_장소_검증용_질문에_넣지_않는다() {
        // 원문에 없는 장소(강남역)를 판정 LLM이 검색어에 지어낸 경우입니다.
        var subQuestions = List.of(
                new SubQuestionFaqs("강남역 근처 매장", List.of(), "근처 매장이랑"),
                new SubQuestionFaqs("매장 영업시간", List.of(), "영업시간도 알려줘"));
        var materials = AnswerMaterials.builder("근처 매장이랑 영업시간도 알려줘").enableTool(AiTool.STORE_SEARCH)
                .subQuestions(subQuestions).build();
        when(promptService.createPrompt("근처 매장이랑 영업시간도 알려줘", List.of(), subQuestions, List.of(), List.of()))
                .thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));

        aiService.generateAnswer(materials);

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().toolContext())
                .containsEntry(StoreTools.QUESTION, "근처 매장이랑 영업시간도 알려줘");
    }

    @Test
    void 도구가_조회한_매장은_답변과_함께_반환한다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).build();
        var storeMap = new StoreMapResult(new Location(37.5, 127.0), null, 3.0, List.of());
        when(promptService.createPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
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
        when(promptService.createPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("어느 지역인지 알려 주세요."));

        assertThat(aiService.generateAnswer(materials).storeMap()).isNull();
    }

    @Test
    void 도구가_위치_필요를_표시하면_locationRequired를_반환한다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
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
        when(promptService.createPrompt("근처 매장", List.of(), List.of(), List.of(section), List.of())).thenReturn(prompt);
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
        when(promptService.createPrompt("질문", faqs, List.of(), List.of(), List.of())).thenThrow(failure);

        assertThatThrownBy(() -> aiService.generateAnswer(AnswerMaterials.builder("질문").addFaqs(faqs).build()))
                .isSameAs(failure);
        verifyNoInteractions(llmService);
    }

    @Test
    void JSON_파싱_후_문제_문장을_제거하고_남은_본문만_반환한다() {
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).build();
        when(promptService.createPrompt("사용자 질문", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(
                "{\"answer\":\"신분증을 지참해주세요. 바보입니다.\",\"evidence_ids\":\"123\",\"status\":\"CLARIFY\"}"));

        assertThat(aiService.generateAnswer(materials).answer()).isEqualTo("신분증을 지참해주세요.");
        verify(llmService).generateAnswer(prompt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"바보입니다.",
            "{\"answer\":\"바보입니다.\",\"evidence_ids\":[],\"status\":\"ANSWER\"}",
            "```json\n{\"answer\":\"바보입니다.\"}\n```"})
    void 일반_문장과_JSON_모두_정상_본문이_없으면_출력_차단_오류를_전달한다(String output) {
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).build();
        when(promptService.createPrompt("사용자 질문", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(output));

        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
        verify(llmService).generateAnswer(prompt);
    }

    @Test
    void 실제_제공한_FAQ로_수치_충돌을_확인해_문제_문장을_제거한다() {
        var sources = List.of(new FaqSearchResponseDto(1L, "할인율", "할인율은 25%입니다.", 0.9, Intent.GENERAL));
        var materials = AnswerMaterials.builder("할인율").addFaqs(sources).build();
        when(promptService.createPrompt("할인율", sources, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(
                "{\"answer\":\"할인율은 50%입니다. 고객센터에서 자세한 조건을 확인해주세요.\",\"status\":\"ANSWER\",\"evidence_ids\":[\"1\"]}"));

        assertThat(aiService.generateAnswer(materials).answer()).isEqualTo("고객센터에서 자세한 조건을 확인해주세요.");
    }

    @Test
    void 매장_도구가_켜져도_실제_조회_결과_없이_조회했다고_하면_거절한다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("근처 매장을 조회했습니다."));

        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
    }

    @Test
    void 출력_검사는_이전_지도보다_현재_도구의_실제_결과를_사용한다() {
        var previous = new StoreMapResult(new Location(37.5, 127), null, 5.0, List.of());
        var actual = new StoreMapResult(new Location(37.5, 127), "강남역", 3.0, List.of());
        var materials = AnswerMaterials.builder("근처 매장").storeMap(previous).enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            ((StoreSearchRecorder) request.toolContext().get(StoreTools.RECORDER)).record(actual);
            return new LlmResponseDto("근처 매장을 조회했습니다. 반경 3km 안에 매장이 없습니다.");
        });

        AiAnswer answer = aiService.generateAnswer(materials);
        assertThat(answer.answer()).isEqualTo("근처 매장을 조회했습니다. 반경 3km 안에 매장이 없습니다.");
        assertThat(answer.storeMap()).isSameAs(actual);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void 기존_빈_응답은_그대로_후속_답변_검사에_넘긴다(String output) {
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).build();
        when(promptService.createPrompt("사용자 질문", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(output));

        assertThat(aiService.generateAnswer(materials).answer()).isNull();
    }
}
