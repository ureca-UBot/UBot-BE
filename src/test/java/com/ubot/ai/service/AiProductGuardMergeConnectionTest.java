package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.config.AiOutputGuardProperties;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.OutputGuardResult;
import com.ubot.ai.dto.ProductDataCards.ProductCard;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.AiToolRegistry;
import com.ubot.ai.tool.UserDataRecorder;
import com.ubot.ai.tool.UserDataTools;
import com.ubot.ai.tool.product.ProductDataRecorder;
import com.ubot.ai.tool.product.ProductListing;
import com.ubot.ai.tool.product.ProductType;
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.billing.enums.PaymentStatus;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.prompt.service.PromptService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;

/** 상품 조회와 출력 검사 경계만 확인합니다. 도구 조회·DB·HTTP·실제 모델은 호출하지 않습니다. */
class AiProductGuardMergeConnectionTest {
    private static final ProductCard PREMIUM_89 = new ProductCard(ProductType.PLAN,
            "Ubot 프리미엄 89", 89000, List.of(), List.of());
    private static final ProductCard PREMIUM_99 = new ProductCard(ProductType.PLAN,
            "Ubot 프리미엄 99", 99000, List.of(), List.of());
    private static final ProductListing LISTING = new ProductListing("프리미엄 요금제", "월정액 낮은 순", 2,
            List.of(PREMIUM_89, PREMIUM_99));
    private static final BillingRecordResponseDto BILLING = new BillingRecordResponseDto(71L,
            YearMonth.of(2026, 9), 30000, 0, 0, 0, 0, 30000,
            LocalDate.of(2026, 9, 25), PaymentStatus.PAID, 30000, null, null);
    private static final String MODEL_RESPONSE = "{\"answer\":\"상품 정보가 부족합니다.\","
            + "\"evidence_ids\":[\"17\"],\"status\":\"PARTIAL\"}";

    private final PromptService prompts = mock(PromptService.class);
    private final LlmService generation = mock(LlmService.class);
    private final AiToolRegistry registry = mock(AiToolRegistry.class);
    private final AiOutputGuardService code = mock(AiOutputGuardService.class);
    private final AiLlmOutputGuardService ai = mock(AiLlmOutputGuardService.class);
    private final AiOutputGuardProperties properties = new AiOutputGuardProperties();
    private final AiService service = new AiService(prompts, generation, registry, code,
            SubQuestionLabels.withoutMorphology(), ai, properties, org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCaptureService.class), org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));

    @Test
    void 출력_검사를_꺼도_상품_목록과_전체_상품_카드는_유지된다() {
        properties.setEnabled(false);
        var materials = listingMaterials();
        prepare(materials, this::recordListing, MODEL_RESPONSE);

        var answer = service.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo(LISTING.answer());
        assertThat(answer.status()).isEqualTo(SubQuestionStatuses.ANSWER);
        assertThat(answer.productData().products()).containsExactly(PREMIUM_89, PREMIUM_99);
        assertThat(answer.outputGuardResult().reasons()).isEmpty();
        verify(generation, times(1)).generateAnswer(any());
        verifyNoInteractions(code, ai);
    }

    @Test
    void 코드가_만든_목록은_코드_검사만_거치고_목록과_상품_카드를_유지한다() {
        var materials = listingMaterials();
        prepare(materials, this::recordListing, MODEL_RESPONSE);

        var answer = service.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo(LISTING.answer());
        assertThat(answer.productData().products()).containsExactly(PREMIUM_89, PREMIUM_99);
        var inspected = ArgumentCaptor.forClass(AnswerMaterials.class);
        verify(code).inspectAnswer(eq(LISTING.answer()), eq(List.of()), inspected.capture(), isNull());
        assertThat(inspected.getValue().sections()).anySatisfy(section -> {
            assertThat(section.title()).isEqualTo(ProductDataRecorder.SECTION_TITLE);
            assertThat(section.content()).contains(PREMIUM_89.name(), PREMIUM_99.name());
        });
        verify(generation, times(1)).generateAnswer(any());
        verifyNoInteractions(ai);
    }

    @Test
    void 상품과_청구서_조회_기록을_함께_검사하고_답변에_쓴_두_카드를_유지한다() {
        var original = new ContextSection("공식 안내", "요금제와 청구서는 조회 결과로 안내합니다.");
        var materials = AnswerMaterials.builder("프리미엄 요금제와 9월 청구서를 알려줘.")
                .enableTool(AiTool.PLAN_SEARCH).enableTool(AiTool.USER_DATA_LOOKUP)
                .addSection(original).userId(7L).build();
        String body = "Ubot 프리미엄 89의 월 요금은 89,000원입니다. 9월 청구서는 30,000원입니다.";
        prepare(materials, request -> {
            recordListing(request);
            assertThat(request.toolContext().get(UserDataTools.USER_ID)).isEqualTo(7L);
            var recorder = (UserDataRecorder) request.toolContext().get(UserDataTools.RECORDER);
            recorder.recordBilling("010-****-1234", BILLING);
        }, "{\"answer\":\"" + body + "\",\"evidence_ids\":[],\"status\":\"ANSWER\"}");

        var answer = service.generateAnswer(materials);

        // 상품 이외 도구도 있는 복합 질문은 코드 목록으로 대체하지 않습니다.
        assertThat(answer.answer()).isEqualTo(body);
        assertThat(answer.productData().products()).containsExactly(PREMIUM_89);
        assertThat(answer.userData().billings()).singleElement().satisfies(card -> {
            assertThat(card.phoneNumber()).isEqualTo("010-****-1234");
            assertThat(card.billing()).isEqualTo(BILLING);
        });
        var inspected = ArgumentCaptor.forClass(AnswerMaterials.class);
        verify(code).inspectAnswer(eq(body), eq(List.of()), inspected.capture(), isNull());
        AnswerMaterials evidence = inspected.getValue();
        assertThat(evidence.sections()).contains(original).anySatisfy(section -> {
            assertThat(section.title()).isEqualTo(ProductDataRecorder.SECTION_TITLE);
            assertThat(section.content()).contains(PREMIUM_89.name(), PREMIUM_99.name());
        }).anySatisfy(section -> {
            assertThat(section.title()).isEqualTo(UserDataRecorder.SECTION_TITLE);
            assertThat(section.content()).contains("9월 청구서", "30,000원");
        });
        assertThat(evidence.toolRecords().billings()).singleElement()
                .satisfies(card -> assertThat(card.billing()).isEqualTo(BILLING));
        assertThat(materials.sections()).containsExactly(original);
        assertThat(materials.toolRecords()).isNull();
        verify(ai, times(1)).validate(eq(body), eq(List.of()), same(evidence), isNull());
        verify(generation, times(1)).generateAnswer(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REJECTED", "CONTENT_REMOVED"})
    void 상품_목록_검사가_거절하거나_내용을_삭제하면_전체를_거절하고_재생성하지_않는다(String result) {
        var materials = listingMaterials();
        prepare(materials, this::recordListing, MODEL_RESPONSE);
        boolean rejected = "REJECTED".equals(result);
        when(code.inspectAnswer(any(), any(), any(), any())).thenReturn(new OutputGuardResult(
                LISTING.answer(), rejected, Set.of(OutputGuardResult.Reason.ANSWER_ERROR), !rejected));

        assertThatThrownBy(() -> service.generateAnswer(materials))
                .isInstanceOfSatisfying(LlmException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));

        verify(code, times(1)).inspectAnswer(any(), any(), any(), any());
        verify(generation, times(1)).generateAnswer(any());
        verifyNoInteractions(ai);
    }

    private AnswerMaterials listingMaterials() {
        return AnswerMaterials.builder("프리미엄 요금제 종류 알려줘").enableTool(AiTool.PLAN_SEARCH).build();
    }

    private void prepare(AnswerMaterials materials, Consumer<LlmRequestDto> record, String response) {
        when(registry.resolve(materials.tools())).thenReturn(List.of(mock(ToolCallback.class)));
        when(prompts.createSlotPrompt(materials.question(), materials.faqs(), materials.subQuestions(),
                materials.sections(), materials.history())).thenReturn(new LlmRequestDto(List.of()));
        when(generation.generateAnswer(any())).thenAnswer(invocation -> {
            record.accept(invocation.getArgument(0));
            return new LlmResponseDto(response);
        });
        when(code.inspectAnswer(any(), any(), any(), any())).thenAnswer(invocation ->
                new OutputGuardResult(invocation.getArgument(0), false, Set.of()));
    }

    private void recordListing(LlmRequestDto request) {
        var recorder = (ProductDataRecorder) request.toolContext().get(ProductDataRecorder.CONTEXT_KEY);
        recorder.record(PREMIUM_89, "- Ubot 프리미엄 89 | 월정액 89,000원");
        recorder.record(PREMIUM_99, "- Ubot 프리미엄 99 | 월정액 99,000원");
        recorder.searched(LISTING);
    }
}
