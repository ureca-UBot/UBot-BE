package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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
import com.ubot.ai.dto.UserDataCards;
import com.ubot.ai.dto.UserDataSummary;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.AiToolFixtures;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.ai.tool.UserDataRecorder;
import com.ubot.ai.tool.UserDataTools;
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.billing.enums.PaymentStatus;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
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
    void 모델이_낸_JSON에서_answer만_꺼내_반환한다() {
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).build();
        when(promptService.createPrompt("사용자 질문", faqs, List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto(
                "{\"answer\":\"선택약정은 12개월과 24개월이 있습니다.\",\"evidence_ids\":[\"112\"],\"status\":\"ANSWER\"}"));

        assertThat(aiService.generateAnswer(materials).answer()).isEqualTo("선택약정은 12개월과 24개월이 있습니다.");
    }

    @Test
    void 모델이_낸_JSON을_읽을_수_없으면_LLM_응답_오류로_실패한다() {
        var materials = AnswerMaterials.builder("사용자 질문").addFaqs(faqs).build();
        when(promptService.createPrompt("사용자 질문", faqs, List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(prompt)).thenReturn(new LlmResponseDto("{\"answer\": \"잘린 답"));

        assertThatThrownBy(() -> aiService.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LlmErrorCode.LLM_RESPONSE_INVALID));
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
                .hasEntrySatisfying(UserDataTools.RECORDER,
                        recorder -> assertThat(recorder).isInstanceOf(UserDataRecorder.class))
                .hasSize(3);
    }

    @Test
    void 가입_정보_도구가_켜지면_로그인_사용자_ID를_toolContext로만_전달한다() {
        var materials = AnswerMaterials.builder("지난달 요금").enableTool(AiTool.USER_DATA_LOOKUP).userId(7L).build();
        when(promptService.createPrompt("지난달 요금", List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));

        aiService.generateAnswer(materials);

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().toolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("getUsageByMonth", "getBillingByMonth");
        assertThat(request.getValue().toolContext()).containsEntry(UserDataTools.USER_ID, 7L);
    }

    @Test
    void 비회원이면_toolContext에_사용자_ID를_넣지_않는다() {
        var materials = AnswerMaterials.builder("근처 매장").enableTool(AiTool.STORE_SEARCH).userId(null).build();
        when(promptService.createPrompt("근처 매장", List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("답변"));

        aiService.generateAnswer(materials);

        ArgumentCaptor<LlmRequestDto> request = ArgumentCaptor.forClass(LlmRequestDto.class);
        verify(llmService).generateAnswer(request.capture());
        assertThat(request.getValue().toolContext()).doesNotContainKey(UserDataTools.USER_ID);
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
    void 도구가_조회한_청구서와_답변에_쓰인_요약_청구서를_중복_없이_합쳐_카드로_반환한다() {
        var materials = AnswerMaterials.builder("지난달이랑 이번 달 요금 차이").enableTool(AiTool.USER_DATA_LOOKUP).userId(7L)
                .userDataSummary(summaryWithLatestBilling(YearMonth.of(2026, 10))).build();
        when(promptService.createPrompt("지난달이랑 이번 달 요금 차이", List.of(), List.of())).thenReturn(prompt);
        // 실제 도구 실행 대신, 도구가 9월 청구서와 (이미 요약에 있는) 10월 청구서를 recorder에 남기는 상황을 흉내 냅니다.
        when(llmService.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            var recorder = (UserDataRecorder) request.toolContext().get(UserDataTools.RECORDER);
            recorder.recordBilling("010-****-5678", billing(YearMonth.of(2026, 9)));
            recorder.recordBilling("010-****-5678", billing(YearMonth.of(2026, 10)));
            return new LlmResponseDto("{\"answer\":\"2026년 10월 청구서는 9월 청구서보다 1,000원 많습니다.\","
                    + "\"evidence_ids\":[],\"status\":\"ANSWER\"}");
        });

        AiAnswer answer = aiService.generateAnswer(materials);

        assertThat(answer.userData().billings())
                .extracting(card -> card.billing().billingMonth(), UserDataCards.BillingCard::usageMonth)
                .containsExactly(tuple(YearMonth.of(2026, 9), 8), tuple(YearMonth.of(2026, 10), 9));
        assertThat(answer.userData().plans()).isEmpty();
    }

    @Test
    void 도구를_부르지_않으면_답변에_쓰인_요약_기록만_카드로_반환한다() {
        var materials = AnswerMaterials.builder("이번 달 요금 얼마야?").enableTool(AiTool.USER_DATA_LOOKUP).userId(7L)
                .userDataSummary(summaryWithLatestBilling(YearMonth.of(2026, 10))).build();
        when(promptService.createPrompt("이번 달 요금 얼마야?", List.of(), List.of())).thenReturn(prompt);
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
        when(promptService.createPrompt("결합 상품 있어?", List.of(), List.of())).thenReturn(prompt);
        when(llmService.generateAnswer(any())).thenReturn(new LlmResponseDto("결합 상품은 없습니다."));

        assertThat(aiService.generateAnswer(materials).userData()).isNull();
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
}
