package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.dto.UserDataSummary;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.AiToolFixtures;
import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.product.PlanSeriesOverview;
import com.ubot.ai.tool.product.ProductDataRecorder;
import com.ubot.ai.tool.product.ProductListing;
import com.ubot.ai.tool.product.ProductType;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.ai.tool.UserDataRecorder;
import com.ubot.ai.tool.UserDataTools;
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.billing.enums.PaymentStatus;
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
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.enums.SimStatus;
import com.ubot.subscription.enums.SimType;
import com.ubot.subscription.enums.SubscriptionStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
        var registry = AiToolFixtures.registry(
                new StoreTools(new NearbyStoreSearcher(mock(StoreService.class), 3.0, 5), mock(LocationService.class)));
        var words = mock(ForbiddenWordRepository.class);
        when(words.findAllByStatus(ForbiddenWordStatus.ACTIVE))
                .thenReturn(List.of(ForbiddenWord.builder().word("바보").status(ForbiddenWordStatus.ACTIVE).build()));
        var wordFilter = new ForbiddenWordFilterService(words);
        wordFilter.refreshForbiddenWordCache();
        aiService = new AiService(promptService, llmService, registry, new AiOutputGuardService(wordFilter),
                SubQuestionLabels.withoutMorphology());
    }

    @Test
    void 생성된_프롬프트를_도구_없이_LLM에_전달하고_응답을_반환한다() {
        var section = new ContextSection("사용자 정보", "이름: 홍길동");
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).addSection(section).build();
        when(promptService.createSlotPrompt("사용자 질문", faqs, List.of(), List.of(section), List.of())).thenReturn(prompt);
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
        when(promptService.createSlotPrompt("사용자 질문", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(
                "{\"answer\":\"선택약정은 12개월과 24개월이 있습니다.\",\"evidence_ids\":[\"1\"],\"status\":\"ANSWER\"}"));

        assertThat(aiService.generateAnswer(materials).answer()).isEqualTo("선택약정은 12개월과 24개월이 있습니다.");
    }

    @Test
    void 모델이_낸_JSON을_읽을_수_없으면_LLM_응답_오류로_실패한다() {
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).build();
        when(promptService.createSlotPrompt("사용자 질문", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto("{\"answer\": \"잘린 답"));

        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"112", "FAQ-999999", "999999"})
    void 근거번호만_틀린_정상_답변은_기존_생성_흐름에서_보존한다(String id) {
        var materials = AnswerMaterials.builder("서류 안내").addFaqs(faqs).build();
        when(promptService.createSlotPrompt("서류 안내", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(
                "{\"answer\":\"신분증을 지참해주세요.\",\"evidence_ids\":[\"" + id + "\"],\"status\":\"ANSWER\"}"));
        assertThat(aiService.generateAnswer(materials).answer()).isEqualTo("신분증을 지참해주세요.");
        verify(llmService).generateAnswer(prompt);
    }

    @Test
    void 매장_도구가_켜지면_도구와_질문_recorder를_toolContext로_전달한다() {
        var materials = AnswerMaterials.builder("근처 매장 알려줘").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createSlotPrompt("근처 매장 알려줘", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
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
                .hasEntrySatisfying(UserDataTools.RECORDER,
                        recorder -> assertThat(recorder).isInstanceOf(UserDataRecorder.class))
                .hasEntrySatisfying(ProductDataRecorder.CONTEXT_KEY,
                        recorder -> assertThat(recorder).isInstanceOf(ProductDataRecorder.class))
                .hasSize(4);
    }

    @Test
    void 가입_정보_도구가_켜지면_로그인_사용자_ID를_toolContext로만_전달한다() {
        var materials = AnswerMaterials.builder("지난달 요금").enableTool(AiTool.USER_DATA_LOOKUP).userId(7L).build();
        when(promptService.createSlotPrompt("지난달 요금", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));

        aiService.generateAnswer(materials);

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().toolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("getUsageByMonth", "getBillingByMonth");
        assertThat(request.getValue().toolContext()).containsEntry(UserDataTools.USER_ID, 7L)
                // 요약이 없으면(미리 조회 실패) 도구가 가장 최근 달도 직접 돌려주도록 표시하지 않습니다.
                .doesNotContainKey(UserDataTools.SUMMARY_PROVIDED);
    }

    @Test
    void 가입_정보_요약이_프롬프트에_들어갔으면_toolContext에_표시한다() {
        var materials = AnswerMaterials.builder("지난달 요금").enableTool(AiTool.USER_DATA_LOOKUP).userId(7L)
                .userDataSummary(summaryWithLatestBilling(YearMonth.of(2026, 10))).build();
        when(promptService.createSlotPrompt("지난달 요금", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));

        aiService.generateAnswer(materials);

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().toolContext()).containsEntry(UserDataTools.SUMMARY_PROVIDED, true);
    }

    @Test
    void 비회원이면_toolContext에_사용자_ID를_넣지_않는다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).userId(null).build();
        when(promptService.createSlotPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));

        aiService.generateAnswer(materials);

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().toolContext()).doesNotContainKey(UserDataTools.USER_ID);
    }

    @Test
    void 이전_대화가_있으면_사용자_질문과_답변을_장소_검증용_질문에_함께_넣는다() {
        var history = List.of(new ConversationTurn("강남역 매장 알려줘", "강남역점이 있습니다."));
        var materials = AnswerMaterials.builder("거기 영업시간은?").enableTool(AiTool.STORE_SEARCH).build()
                .withHistory(history);
        when(promptService.createSlotPrompt("거기 영업시간은?", List.of(), List.of(), List.of(), history)).thenReturn(prompt);
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
        when(promptService.createSlotPrompt("근처 매장이랑 영업시간도 알려줘", List.of(), subQuestions, List.of(), List.of()))
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
        when(promptService.createSlotPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
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
    void 도구가_조회한_청구서와_답변에_쓰인_요약_청구서를_중복_없이_합쳐_카드로_반환한다() {
        var materials = AnswerMaterials.builder("지난달이랑 이번 달 요금 차이").enableTool(AiTool.USER_DATA_LOOKUP).userId(7L)
                .userDataSummary(summaryWithLatestBilling(YearMonth.of(2026, 10))).build();
        when(promptService.createSlotPrompt("지난달이랑 이번 달 요금 차이", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        // 실제 도구 실행 대신, 도구가 9월 청구서와 (이미 요약에 있는) 10월 청구서를 recorder에 남기는 상황을 흉내 냅니다.
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            var recorder = (UserDataRecorder) request.toolContext().get(UserDataTools.RECORDER);
            recorder.recordBilling("010-****-5678", billing(YearMonth.of(2026, 9)));
            recorder.recordBilling("010-****-5678", billing(YearMonth.of(2026, 10)));
            return new LlmResponseDto("{\"answer\":\"2026년 10월 청구서와 9월 청구서는 모두 78,000원으로 같습니다.\","
                    + "\"evidence_ids\":[],\"status\":\"ANSWER\"}");
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.userData().billings())
                .extracting(card -> card.billing().billingMonth(), UserDataCards.BillingCard::usageMonth)
                .containsExactly(tuple(YearMonth.of(2026, 9), 8), tuple(YearMonth.of(2026, 10), 9));
        assertThat(answer.userData().plans()).isEmpty();
    }

    @Test
    void 도구로_조회했지만_답변에_쓰지_않은_달은_카드로_붙이지_않는다() {
        var materials = AnswerMaterials.builder("8월이랑 9월 요금 비교해줘").enableTool(AiTool.USER_DATA_LOOKUP).userId(7L)
                .userDataSummary(summaryWithLatestBilling(YearMonth.of(2026, 10))).build();
        when(promptService.createSlotPrompt("8월이랑 9월 요금 비교해줘", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        // 모델이 7월까지 더 조회했지만 답변에는 8월·9월만 쓴 상황입니다.
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            var recorder = (UserDataRecorder) request.toolContext().get(UserDataTools.RECORDER);
            recorder.recordBilling("010-****-5678", billing(YearMonth.of(2026, 7)));
            recorder.recordBilling("010-****-5678", billing(YearMonth.of(2026, 8)));
            recorder.recordBilling("010-****-5678", billing(YearMonth.of(2026, 9)));
            return new LlmResponseDto("{\"answer\":\"8월 청구서와 9월 청구서는 모두 78,000원으로 같습니다.\","
                    + "\"evidence_ids\":[],\"status\":\"ANSWER\"}");
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.userData().billings()).extracting(card -> card.billing().billingMonth())
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9));
    }

    @Test
    void 도구를_부르지_않으면_답변에_쓰인_요약_기록만_카드로_반환한다() {
        var materials = AnswerMaterials.builder("이번 달 요금 얼마야?").enableTool(AiTool.USER_DATA_LOOKUP).userId(7L)
                .userDataSummary(summaryWithLatestBilling(YearMonth.of(2026, 10))).build();
        when(promptService.createSlotPrompt("이번 달 요금 얼마야?", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto(
                "{\"answer\":\"이번 달 요금은 2026년 10월 청구서 기준 78,000원입니다.\",\"evidence_ids\":[],\"status\":\"ANSWER\"}"));

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.userData().billings()).singleElement().satisfies(card -> {
            assertThat(card.phoneNumber()).isEqualTo("010-****-5678");
            assertThat(card.billing().billingMonth()).isEqualTo(YearMonth.of(2026, 10));
        });
    }

    @Test
    void 답변에_쓰인_가입_정보가_없으면_카드는_없다() {
        var materials = AnswerMaterials.builder("결합 상품 있어?").enableTool(AiTool.USER_DATA_LOOKUP).userId(7L)
                .userDataSummary(summaryWithLatestBilling(YearMonth.of(2026, 10))).build();
        when(promptService.createSlotPrompt("결합 상품 있어?", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("결합 상품은 없습니다."));

        assertThat(aiService.generateAnswer(materials).userData()).isNull();
    }

    @Test
    void 모델이_답을_보류하면_조회한_기록이_있어도_카드를_붙이지_않고_보류_상태는_유지한다() {
        var materials = AnswerMaterials.builder("요금제 변경할 수 있어?").enableTool(AiTool.USER_DATA_LOOKUP).userId(7L)
                .userDataSummary(summaryWithLatestBilling(YearMonth.of(2026, 10))).build();
        when(promptService.createSlotPrompt("요금제 변경할 수 있어?", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        // 도구가 청구서를 남기고, 답변에도 요약 청구서가 나오지만 모델은 답을 보류한 상황입니다.
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            var recorder = (UserDataRecorder) request.toolContext().get(UserDataTools.RECORDER);
            recorder.recordBilling("010-****-5678", billing(YearMonth.of(2026, 9)));
            return new LlmResponseDto("{\"answer\":\"2026년 10월 청구서만으로는 요금제 변경 가능 여부를 확인할 수 없습니다.\","
                    + "\"evidence_ids\":[],\"status\":\"ABSTAIN\"}");
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.userData()).isNull();
        assertThat(answer.abstained()).isTrue();
    }

    @Test
    void 상품_도구가_조회한_상품_중_답변에_쓰인_것만_카드로_반환하고_가격을_검사_근거로_쓴다() {
        var materials = AnswerMaterials.builder("밸런스 요금제 얼마야?").enableTool(AiTool.PLAN_SEARCH).build();
        when(promptService.createSlotPrompt("밸런스 요금제 얼마야?", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        // 실제 도구 실행 대신, 도구가 요금제 두 개를 돌려주고 모델이 그중 하나만 답변에 쓴 상황입니다.
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            var recorder = (ProductDataRecorder) request.toolContext().get(ProductDataRecorder.CONTEXT_KEY);
            recorder.record(new ProductCard(ProductType.PLAN, "Ubot 밸런스 59", 59000, List.of(), List.of()),
                    "- Ubot 밸런스 59 | 월정액 59,000원 | 약정 할인 적용 시 월 44,250원");
            recorder.record(new ProductCard(ProductType.PLAN, "Ubot 밸런스 69", 69000, List.of(), List.of()),
                    "- Ubot 밸런스 69 | 월정액 69,000원 | 약정 할인 적용 시 월 51,750원");
            return new LlmResponseDto("{\"answer\":\"Ubot 밸런스 59의 월정액은 59,000원입니다.\","
                    + "\"evidence_ids\":[],\"status\":\"ANSWER\"}");
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        // 59,000원은 프롬프트 자료가 아니라 도구 결과에만 있으므로, 검사 근거에 덧붙이지 않았다면 지워졌을 문장입니다.
        assertThat(answer.answer()).isEqualTo("Ubot 밸런스 59의 월정액은 59,000원입니다.");
        assertThat(answer.productData().products()).extracting(ProductCard::name).containsExactly("Ubot 밸런스 59");
    }

    @Test
    void 목록_요청_조회가_한_번이면_LLM_답변_대신_목록_답변과_목록_전체_카드를_반환한다() {
        var materials = AnswerMaterials.builder("프리미엄 요금제 종류 알려줘").enableTool(AiTool.PLAN_SEARCH).build();
        when(promptService.createSlotPrompt("프리미엄 요금제 종류 알려줘", List.of(), List.of(), List.of(), List.of()))
                .thenReturn(prompt);
        // 도구가 목록 요청으로 두 요금제를 돌려줬는데 모델은 하나만 쓰고 정보가 부족하다고 답한 상황입니다.
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            recordPremiumListing(invocation.getArgument(0));
            return new LlmResponseDto("{\"answer\":\"Ubot 프리미엄 89만 확인되어 정보가 부족합니다.\","
                    + "\"evidence_ids\":[\"12\"],\"status\":\"PARTIAL\"}");
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo("""
                프리미엄 요금제는 다음 2가지가 있습니다.
                - Ubot 프리미엄 89 (월 89,000원)
                - Ubot 프리미엄 99 (월 99,000원)""");
        assertThat(answer.status()).isEqualTo("ANSWER");
        assertThat(answer.productData().products()).extracting(ProductCard::name)
                .containsExactly("Ubot 프리미엄 89", "Ubot 프리미엄 99");
    }

    @Test
    void 하위_질문이_여럿이면_목록_요청이어도_LLM_답변을_쓴다() {
        var subQuestions = List.of(
                new SubQuestionFaqs("프리미엄 요금제 종류", List.of(), "프리미엄 요금제 종류랑"),
                new SubQuestionFaqs("요금제 해지 방법", List.of(), "해지 방법 알려줘"));
        var materials = AnswerMaterials.builder("프리미엄 요금제 종류랑 해지 방법 알려줘").enableTool(AiTool.PLAN_SEARCH)
                .subQuestions(subQuestions).build();
        when(promptService.createSlotPrompt("프리미엄 요금제 종류랑 해지 방법 알려줘", List.of(), subQuestions, List.of(), List.of()))
                .thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            recordPremiumListing(invocation.getArgument(0));
            return new LlmResponseDto("{\"answer\":\"Ubot 프리미엄 89와 Ubot 프리미엄 99가 있습니다.\","
                    + "\"evidence_ids\":[],\"status\":\"PARTIAL\"}");
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo("Ubot 프리미엄 89와 Ubot 프리미엄 99가 있습니다.");
        assertThat(answer.status()).isEqualTo("PARTIAL");
    }

    @Test
    void 시리즈_개요_답변은_출력_검사를_그대로_통과하고_카드를_붙이지_않는다() {
        var materials = AnswerMaterials.builder("요금제 종류 알려줘").enableTool(AiTool.PLAN_SEARCH).build();
        when(promptService.createSlotPrompt("요금제 종류 알려줘", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        var overview = new PlanSeriesOverview(List.of(
                new PlanSeriesOverview.Group("실속", true, 13, 3300, 19800),
                new PlanSeriesOverview.Group("프리미엄", true, 5, 89000, 129000)));
        // 요금제 도구가 월정액 낮은 순 행을 돌려주고, 개요와 그 수치를 기록한 상황입니다.
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            var recorder = (ProductDataRecorder) request.toolContext().get(ProductDataRecorder.CONTEXT_KEY);
            recorder.record(new ProductCard(ProductType.PLAN, "Ubot 실속 LTE 500MB/60분", 3300, List.of(), List.of()),
                    "- Ubot 실속 LTE 500MB/60분 | LTE | 대상 일반 | 월정액 3,300원");
            recorder.note(overview.answer());
            recorder.searched(overview);
            return new LlmResponseDto("{\"answer\":\"요금제 정보가 부족합니다.\",\"evidence_ids\":[],\"status\":\"PARTIAL\"}");
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo(overview.answer());
        assertThat(answer.status()).isEqualTo("ANSWER");
        assertThat(answer.productData()).isNull();
    }

    @Test
    void 목록_요청을_조회한_뒤_LLM_응답이_깨져도_목록_답변으로_답한다() {
        var materials = AnswerMaterials.builder("프리미엄 요금제 하나만 알려줘").enableTool(AiTool.PLAN_SEARCH).build();
        when(promptService.createSlotPrompt("프리미엄 요금제 하나만 알려줘", List.of(), List.of(), List.of(), List.of()))
                .thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            recordPremiumListing(invocation.getArgument(0));
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).startsWith("프리미엄 요금제는 다음 2가지가 있습니다.");
        assertThat(answer.status()).isEqualTo("ANSWER");
        assertThat(answer.productData().products()).extracting(ProductCard::name)
                .containsExactly("Ubot 프리미엄 89", "Ubot 프리미엄 99");
    }

    @Test
    void 목록이_없거나_응답_형식_오류가_아니면_LLM_오류를_그대로_던진다() {
        var materials = AnswerMaterials.builder("프리미엄 요금제 알려줘").enableTool(AiTool.PLAN_SEARCH).build();
        when(promptService.createSlotPrompt("프리미엄 요금제 알려줘", List.of(), List.of(), List.of(), List.of()))
                .thenReturn(prompt);
        when(llmService.generateAnswer(any()))
                .thenThrow(new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID))
                .thenAnswer(invocation -> {
                    recordPremiumListing(invocation.getArgument(0));
                    throw new LlmException(LlmErrorCode.LLM_TIMEOUT);
                });

        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_TIMEOUT));
    }

    private static void recordPremiumListing(LlmRequestDto request) {
        var recorder = (ProductDataRecorder) request.toolContext().get(ProductDataRecorder.CONTEXT_KEY);
        var premium89 = new ProductCard(ProductType.PLAN, "Ubot 프리미엄 89", 89000, List.of(), List.of());
        var premium99 = new ProductCard(ProductType.PLAN, "Ubot 프리미엄 99", 99000, List.of(), List.of());
        recorder.record(premium89, "- Ubot 프리미엄 89 | 5G | 대상 일반 | 월정액 89,000원");
        recorder.record(premium99, "- Ubot 프리미엄 99 | 5G | 대상 일반 | 월정액 99,000원");
        recorder.searched(new ProductListing("프리미엄 요금제", "월정액 낮은 순", 2, List.of(premium89, premium99)));
    }

    @Test
    void 모델이_답을_보류하면_조회한_상품이_있어도_상품_카드를_붙이지_않는다() {
        var materials = AnswerMaterials.builder("컬러링 얼마야?").enableTool(AiTool.ADDON_SEARCH).build();
        when(promptService.createSlotPrompt("컬러링 얼마야?", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            ((ProductDataRecorder) request.toolContext().get(ProductDataRecorder.CONTEXT_KEY)).record(
                    new ProductCard(ProductType.ADDON, "Ubot 컬러링", 990, List.of(), List.of()), "- Ubot 컬러링 | 월 990원");
            return new LlmResponseDto("{\"answer\":\"Ubot 컬러링 요금은 확인할 수 없습니다.\","
                    + "\"evidence_ids\":[],\"status\":\"ABSTAIN\"}");
        });

        assertThat(aiService.generateAnswer(materials).productData()).isNull();
    }

    @Test
    void 도구가_매장을_조회하지_않으면_지도_정보는_없다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createSlotPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("어느 지역인지 알려 주세요."));

        assertThat(aiService.generateAnswer(materials).storeMap()).isNull();
    }

    @Test
    void 도구가_위치_필요를_표시하면_locationRequired를_반환한다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createSlotPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
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
        when(promptService.createSlotPrompt("근처 매장", List.of(), List.of(), List.of(section), List.of())).thenReturn(prompt);
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
        when(promptService.createSlotPrompt("질문", faqs, List.of(), List.of(), List.of())).thenThrow(failure);

        assertThatThrownBy(() -> aiService.generateAnswer(AnswerMaterials.builder("질문").addFaqs(faqs).build()))
                .isSameAs(failure);
        verifyNoInteractions(llmService);
    }

    /** 회선 하나에 최근 청구서만 있는 가입 정보입니다. */
    private static UserDataSummary summaryWithLatestBilling(YearMonth month) {
        var subscription = new SubscriptionResponseDto(1L, "010-1234-5678", SubscriptionStatus.ACTIVE,
                LocalDateTime.of(2024, 3, 2, 10, 0), null, 1L, "5G 스탠다드", SimType.USIM, "********1234",
                SimStatus.ACTIVE, LocalDateTime.of(2024, 3, 2, 10, 0), null);
        return new UserDataSummary(LocalDate.of(2026, 10, 12), new UserDataSummary.Profile("홍길동", 30, "서울", null),
                List.of(new UserDataSummary.SubscriptionSummary(subscription, null, null, billing(month), List.of(), null,
                        List.of(), List.of(), List.of())));
    }

    private static BillingRecordResponseDto billing(YearMonth month) {
        return new BillingRecordResponseDto(1L, month, 75000, 3000, 0, 0, 0, 78000, month.atDay(25),
                PaymentStatus.UNPAID, 0, null, null);
    }

    @Test
    void JSON_파싱_후_문제_문장을_제거하고_남은_본문만_반환한다() {
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).build();
        when(promptService.createSlotPrompt("사용자 질문", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
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
        when(promptService.createSlotPrompt("사용자 질문", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(output));

        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
        verify(llmService).generateAnswer(prompt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"바보입니다.", "할인율은 50%입니다.", "가입을 완료했습니다."})
    void 잘못된_근거번호가_있어도_본문_검사를_거쳐_위험_답변은_거절한다(String answer) {
        var sources = List.of(new FaqSearchResponseDto(1L, "할인율", "할인율은 25%입니다.", 0.9, Intent.GENERAL));
        var materials = AnswerMaterials.builder("할인율").addFaqs(sources).build();
        when(promptService.createSlotPrompt("할인율", sources, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(
                "{\"answer\":\"" + answer + "\",\"status\":\"ANSWER\",\"evidence_ids\":[\"999999\"]}"));
        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
        verify(llmService).generateAnswer(prompt);
    }

    @Test
    void 실제_제공한_FAQ로_수치_충돌을_확인해_문제_문장을_제거한다() {
        var sources = List.of(new FaqSearchResponseDto(1L, "할인율", "할인율은 25%입니다.", 0.9, Intent.GENERAL));
        var materials = AnswerMaterials.builder("할인율").addFaqs(sources).build();
        when(promptService.createSlotPrompt("할인율", sources, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(
                "{\"answer\":\"할인율은 50%입니다. 고객센터에서 자세한 조건을 확인해주세요.\",\"status\":\"ANSWER\",\"evidence_ids\":[\"1\"]}"));

        assertThat(aiService.generateAnswer(materials).answer()).isEqualTo("고객센터에서 자세한 조건을 확인해주세요.");
    }

    @Test
    void 매장_도구가_켜져도_실제_조회_결과_없이_조회했다고_하면_거절한다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createSlotPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
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
        when(promptService.createSlotPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
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
        when(promptService.createSlotPrompt("사용자 질문", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(output));

        assertThat(aiService.generateAnswer(materials).answer()).isNull();
    }

    @Test
    void 칸_형식이면_ANSWER_칸만_보여_주고_답하지_못한_하위_질문은_원문_조각으로_고지한다() {
        var subQuestions = List.of(
                new SubQuestionFaqs("유심 재발급 방법", faqs, "유심 재발급은 어떻게 하고"),
                new SubQuestionFaqs("유심 재발급 비용", faqs, "비용은 얼마예요?"));
        var materials = AnswerMaterials.builder("유심 재발급은 어떻게 하고 비용은 얼마예요?").addFaqs(faqs)
                .subQuestions(subQuestions).build();
        when(promptService.createSlotPrompt(materials.question(), faqs, subQuestions, List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto("{\"answers\":["
                + "{\"answer\":\"매장에서 재발급할 수 있습니다.\",\"status\":\"ANSWER\"},"
                + "{\"answer\":\"비용은 자료에 없습니다.\",\"status\":\"ABSTAIN\"}],\"evidence_ids\":[\"1\"]}"));

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo("매장에서 재발급할 수 있습니다.\n\n"
                + "「비용은 얼마예요」에 대해서는 현재 안내 자료로는 확인해 드리기 어렵습니다.");
        assertThat(answer.status()).isEqualTo("PARTIAL");
    }

    @Test
    void 칸_형식에서_모든_칸이_ABSTAIN이면_가드를_거치지_않고_전체_고지만_낸다() {
        var subQuestions = List.of(
                new SubQuestionFaqs("유심 재발급 비용", faqs, "유심 재발급 비용이랑"),
                new SubQuestionFaqs("eSIM 발급 비용", faqs, "eSIM 비용은요?"));
        var materials = AnswerMaterials.builder("유심 재발급 비용이랑 eSIM 비용은요?").addFaqs(faqs)
                .subQuestions(subQuestions).build();
        when(promptService.createSlotPrompt(materials.question(), faqs, subQuestions, List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto("{\"answers\":["
                + "{\"answer\":\"자료에 없습니다.\",\"status\":\"ABSTAIN\"},"
                + "{\"answer\":\"\",\"status\":\"ABSTAIN\"}],\"evidence_ids\":[]}"));

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo("문의하신 내용은 현재 안내 자료로는 확인해 드리기 어렵습니다.");
        assertThat(answer.abstained()).isTrue();
        assertThat(answer.outputGuardResult()).isNull();
    }

    @Test
    void 칸_형식에서_ANSWER인데_본문이_비었으면_답하지_못한_것으로_본다() {
        var materials = AnswerMaterials.builder("유심 재발급 비용").addFaqs(faqs).build();
        when(promptService.createSlotPrompt("유심 재발급 비용", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(
                "{\"answers\":[{\"answer\":\" \",\"status\":\"ANSWER\"}],\"evidence_ids\":[]}"));

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo("문의하신 내용은 현재 안내 자료로는 확인해 드리기 어렵습니다.");
        assertThat(answer.status()).isEqualTo("ABSTAIN");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"answers\":[{\"answer\":\"답\",\"status\":\"ANSWER\"},{\"answer\":\"답\",\"status\":\"ANSWER\"}],\"evidence_ids\":[]}",
            "{\"answers\":[{\"answer\":\"답\",\"status\":\"PARTIAL\"}],\"evidence_ids\":[]}"})
    void 칸_수가_맞지_않거나_알_수_없는_status면_응답_오류로_실패한다(String output) {
        var materials = AnswerMaterials.builder("유심 재발급 비용").addFaqs(faqs).build();
        when(promptService.createSlotPrompt("유심 재발급 비용", faqs, List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(output));

        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
    }

    @Test
    void 칸_형식에서_가드가_문장을_지운_칸은_답하지_못한_것으로_바꿔_고지한다() {
        var sources = List.of(
                new FaqSearchResponseDto(1L, "할인율", "할인율은 25%입니다.", 0.9, Intent.GENERAL),
                new FaqSearchResponseDto(2L, "가입 장소", "가입은 매장에서 할 수 있습니다.", 0.9, Intent.GENERAL));
        var subQuestions = List.of(
                new SubQuestionFaqs("가입 장소", List.of(sources.get(1)), "가입은 어디서 하고"),
                new SubQuestionFaqs("할인율", List.of(sources.get(0)), "할인율은 얼마예요?"));
        var materials = AnswerMaterials.builder("가입은 어디서 하고 할인율은 얼마예요?").addFaqs(sources)
                .subQuestions(subQuestions).build();
        when(promptService.createSlotPrompt(materials.question(), sources, subQuestions, List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto("{\"answers\":["
                + "{\"answer\":\"가입은 매장에서 할 수 있습니다.\",\"status\":\"ANSWER\"},"
                + "{\"answer\":\"할인율은 50%입니다. 고객센터에서 자세한 조건을 확인해주세요.\",\"status\":\"ANSWER\"}],"
                + "\"evidence_ids\":[\"1\",\"2\"]}"));

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo("가입은 매장에서 할 수 있습니다.\n\n"
                + "「할인율은 얼마예요」에 대해서는 현재 안내 자료로는 확인해 드리기 어렵습니다.");
        assertThat(answer.status()).isEqualTo("PARTIAL");
        // 미응답 수집이 모델의 보류와 가드의 삭제를 구분할 수 있게 칸별 결과를 남깁니다.
        assertThat(answer.slotOutcomes()).containsExactly(
                new AiAnswer.SlotOutcome("ANSWER", false), new AiAnswer.SlotOutcome("ABSTAIN", true));
    }

    @Test
    void 칸_형식에서_정책_위반으로_칸이_거부되면_답변_전체를_거부한다() {
        var subQuestions = List.of(
                new SubQuestionFaqs("유심 재발급 방법", faqs, "유심 재발급은 어떻게 하고"),
                new SubQuestionFaqs("유심 재발급 비용", faqs, "비용은요?"));
        var materials = AnswerMaterials.builder("유심 재발급은 어떻게 하고 비용은요?").addFaqs(faqs)
                .subQuestions(subQuestions).build();
        when(promptService.createSlotPrompt(materials.question(), faqs, subQuestions, List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto("{\"answers\":["
                + "{\"answer\":\"매장에서 재발급할 수 있습니다.\",\"status\":\"ANSWER\"},"
                + "{\"answer\":\"바보입니다.\",\"status\":\"ANSWER\"}],\"evidence_ids\":[]}"));

        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
    }

    @Test
    void 비로그인이면_사용자_정보를_묻는_하위_질문은_로그인_고지를_붙이고_FAQ_부족으로_보지_않는다() {
        var general = new FaqSearchResponseDto(1L, "요금제 변경", "요금제는 앱에서 변경할 수 있습니다.", 0.9, Intent.GENERAL);
        var userData = new FaqSearchResponseDto(2L, "내 요금 조회", "가입 정보에서 확인합니다.", 0.9, Intent.USER_DATA);
        var section = new ContextSection(com.ubot.chat.context.UserIntentHandler.SECTION_TITLE,
                com.ubot.chat.context.UserIntentHandler.LOGIN_REQUIRED_CONTENT);
        var subQuestions = List.of(
                new SubQuestionFaqs("요금제 변경 방법", List.of(general), "요금제는 어떻게 바꾸고"),
                new SubQuestionFaqs("내 이번 달 요금", List.of(userData), "내 요금은 얼마예요?"));
        var materials = AnswerMaterials.builder("요금제는 어떻게 바꾸고 내 요금은 얼마예요?").addFaqs(List.of(general, userData))
                .addSection(section).subQuestions(subQuestions).build();
        when(promptService.createSlotPrompt(materials.question(), List.of(general, userData), subQuestions, List.of(section),
                List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto("{\"answers\":["
                + "{\"answer\":\"요금제는 앱에서 변경할 수 있습니다.\",\"status\":\"ANSWER\"},"
                + "{\"answer\":\"로그인 후 다시 질문해 주세요.\",\"status\":\"ABSTAIN\"}],\"evidence_ids\":[\"1\"]}"));

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo("요금제는 앱에서 변경할 수 있습니다.\n\n"
                + "「내 요금은 얼마예요」에 대해서는 로그인한 사용자만 이용할 수 있습니다. 로그인한 뒤 다시 질문해 주세요.");
        assertThat(answer.slotOutcomes()).containsExactly(
                new AiAnswer.SlotOutcome("ANSWER", false, false), new AiAnswer.SlotOutcome("ABSTAIN", false, true));
    }

    @Test
    void 비로그인으로_질문_전체를_답하지_못하면_로그인_고지_하나만_낸다() {
        var userData = new FaqSearchResponseDto(2L, "내 요금 조회", "가입 정보에서 확인합니다.", 0.9, Intent.USER_DATA);
        var section = new ContextSection(com.ubot.chat.context.UserIntentHandler.SECTION_TITLE,
                com.ubot.chat.context.UserIntentHandler.LOGIN_REQUIRED_CONTENT);
        var materials = AnswerMaterials.builder("내 요금 얼마예요?").addFaqs(List.of(userData)).addSection(section).build();
        when(promptService.createSlotPrompt("내 요금 얼마예요?", List.of(userData), List.of(), List.of(section), List.of()))
                .thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(
                "{\"answers\":[{\"answer\":\"\",\"status\":\"ABSTAIN\"}],\"evidence_ids\":[]}"));

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo("가입 정보는 로그인한 사용자만 이용할 수 있습니다. 로그인한 뒤 다시 질문해 주세요.");
        assertThat(answer.status()).isEqualTo("ABSTAIN");
    }

    @Test
    void 도구_경로도_칸_형식을_쓰고_매장_조회_실패는_사유별_고지로_알린다() {
        var store = new FaqSearchResponseDto(3L, "매장 찾기", "가까운 매장을 안내합니다.", 0.9, Intent.STORE_DATA);
        var materials = AnswerMaterials.builder("근처 매장 알려줘").addFaqs(List.of(store)).enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createSlotPrompt("근처 매장 알려줘", List.of(store), List.of(), List.of(), List.of())).thenReturn(prompt);
        // 도구가 기준 장소를 얻지 못한 상황을 흉내 냅니다. 모델은 안내를 쓰지 않고 칸을 ABSTAIN으로 둡니다.
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            ((StoreSearchRecorder) request.toolContext().get(StoreTools.RECORDER)).markLocationRequired();
            return new LlmResponseDto("{\"answers\":[{\"answer\":\"\",\"status\":\"ABSTAIN\"}],\"evidence_ids\":[]}");
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer())
                .isEqualTo("매장을 찾을 기준 위치가 없어 조회하지 못했습니다. 위치 권한을 허용하거나 지역·역 이름을 알려 주세요.");
        assertThat(answer.locationRequired()).isTrue();
        // 조회 실패로 답하지 못한 것은 FAQ 부족이 아니므로 미응답 수집에서 뺄 수 있게 표시합니다.
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ABSTAIN", false, true));
        verify(promptService, never()).createPrompt(any(), any(), any(), any(), any());
    }

    @Test
    void 도구_경로에서_칸_수가_하위_질문과_다르면_칸을_하나로_합쳐_전체_고지를_낸다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).build();
        when(promptService.createSlotPrompt("근처 매장", List.of(), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("{\"answers\":["
                + "{\"answer\":\"매장 목록은 지도에서 확인할 수 있습니다.\",\"status\":\"ANSWER\"},"
                + "{\"answer\":\"영업시간은 자료에 없습니다.\",\"status\":\"ABSTAIN\"}],\"evidence_ids\":[]}"));

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo("매장 목록은 지도에서 확인할 수 있습니다.\n\n"
                + "문의하신 내용 중 일부는 현재 안내 자료로는 확인해 드리기 어렵습니다.");
        assertThat(answer.status()).isEqualTo("PARTIAL");
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("PARTIAL", false, false));
    }

    @Test
    void 도구_경로에서_회선을_고르지_못하면_후보_회선을_넣어_되묻는다() {
        var userData = new FaqSearchResponseDto(2L, "요금 조회", "가입 정보에서 확인합니다.", 0.9, Intent.USER_DATA);
        var materials = AnswerMaterials.builder("지난달 요금").addFaqs(List.of(userData)).enableTool(AiTool.USER_DATA_LOOKUP)
                .userId(7L).build();
        when(promptService.createSlotPrompt("지난달 요금", List.of(userData), List.of(), List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            ((UserDataRecorder) request.toolContext().get(UserDataTools.RECORDER)).fail(com.ubot.ai.dto.LookupFailure.of(
                    com.ubot.ai.dto.LookupFailure.Kind.SUBSCRIPTION_UNSELECTED, "010-****-1234(5G 스탠다드), 010-****-5678"));
            return new LlmResponseDto("{\"answers\":[{\"answer\":\"\",\"status\":\"ABSTAIN\"}],\"evidence_ids\":[]}");
        });

        assertThat(aiService.generateAnswer(materials).answer())
                .isEqualTo("어느 회선인지 정할 수 없습니다. 다음 중 어느 번호인지 알려 주세요: 010-****-1234(5G 스탠다드), 010-****-5678");
    }
}
