package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ubot.ai.config.AiOutputGuardProperties;
import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.LookupFailure;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.dto.UserDataSummary;
import com.ubot.ai.tool.AiToolFixtures;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.ai.tool.StoreSearchRecorder;
import com.ubot.ai.tool.StoreTools;
import com.ubot.ai.tool.UserDataSummarizer;
import com.ubot.ai.tool.product.ProductNameIndex;
import com.ubot.chat.context.ChatContext;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.context.GeneralIntentHandler;
import com.ubot.chat.context.ProductIntentHandler;
import com.ubot.chat.context.StoreIntentHandler;
import com.ubot.chat.context.UserIntentHandler;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.guardreview.service.ReviewCaptureService;
import com.ubot.guardreview.service.ReviewCheckService;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.service.LlmService;
import com.ubot.location.service.LocationService;
import com.ubot.prompt.service.PromptService;
import com.ubot.store.service.StoreService;
import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 실제 자료 수집기와 핸들러를 거친 뒤에도 조회 실패 사유가 사용자에게 전달되는지 검증합니다. */
class AiLookupFailureNoticeTest {
    private final UserDataSummarizer summarizer = mock(UserDataSummarizer.class);
    private final LlmService llm = mock(LlmService.class);
    private ChatContextCollector collector;
    private AiService ai;

    @BeforeEach
    void setUp() {
        var stores = new NearbyStoreSearcher(mock(StoreService.class), 3.0, 5);
        collector = new ChatContextCollector(List.of(new GeneralIntentHandler(), new StoreIntentHandler(stores),
                new UserIntentHandler(summarizer), new ProductIntentHandler(mock(ProductNameIndex.class))));
        var prompt = mock(PromptService.class);
        when(prompt.createSlotPrompt(any(), any(), any(), any(), any()))
                .thenReturn(new LlmRequestDto(List.of()));
        var properties = new AiOutputGuardProperties();
        properties.setEnabled(false);
        ai = new AiService(prompt, llm,
                AiToolFixtures.registry(new StoreTools(stores, mock(LocationService.class))),
                mock(AiOutputGuardService.class), SubQuestionLabels.withoutMorphology(),
                mock(AiLlmOutputGuardService.class), properties, mock(ReviewCaptureService.class),
                mock(ReviewCheckService.class));
    }

    @ParameterizedTest
    @EnumSource(value = LookupFailure.Kind.class, names = {
            "LOGIN_REQUIRED", "USER_NOT_FOUND", "USER_LOOKUP_FAILED", "NO_ACTIVE_SUBSCRIPTION"})
    void 사용자_FAQ를_근거에서_빼도_실제_실패_사유를_안내한다(LookupFailure.Kind kind) {
        Long userId = 7L;
        switch (kind) {
            case LOGIN_REQUIRED -> userId = null;
            case USER_NOT_FOUND -> when(summarizer.summarize(userId))
                    .thenThrow(new UserException(UserErrorCode.USER_NOT_FOUND));
            case USER_LOOKUP_FAILED -> when(summarizer.summarize(userId)).thenThrow(new IllegalStateException("DB unavailable"));
            case NO_ACTIVE_SUBSCRIPTION -> givenNoSubscription();
            default -> throw new AssertionError(kind);
        }
        var materials = collector.collect(new ChatContext(userId, "내 요금제 알려줘", null),
                List.of(faq(1, Intent.USER_DATA)));
        abstain();

        assertThat(materials.faqs()).isEmpty();
        assertThat(materials.subQuestions().getFirst().faqs()).isEmpty();
        AiAnswer answer = ai.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo(LookupFailure.of(kind).wholeNotice());
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ABSTAIN", false, true));
    }

    @Test
    void 일반_FAQ만_찾았어도_본인_정보_질문에는_로그인_사유를_전달한다() {
        var materials = collector.collect(new ChatContext(null, "내 데이터 사용량 알려줘", null),
                List.of(faq(1, Intent.GENERAL)));
        abstain();

        assertThat(ai.generateAnswer(materials).answer())
                .isEqualTo(LookupFailure.of(LookupFailure.Kind.LOGIN_REQUIRED).wholeNotice());
    }

    @Test
    void 위치가_없으면_매장_FAQ가_제외되어도_위치_안내를_전달한다() {
        var materials = collector.collect(new ChatContext(null, "내 근처 매장 알려줘", null),
                List.of(faq(1, Intent.STORE_DATA)));
        when(llm.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            ((StoreSearchRecorder) request.toolContext().get(StoreTools.RECORDER)).markLocationRequired();
            return response("ABSTAIN", "");
        });

        AiAnswer answer = ai.generateAnswer(materials);

        assertThat(answer.answer()).isEqualTo(LookupFailure.of(LookupFailure.Kind.STORE_NO_LOCATION).wholeNotice());
        assertThat(answer.locationRequired()).isTrue();
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ABSTAIN", false, true));
    }

    @Test
    void 여러_질문의_실패_사유가_서로_섞이거나_정상_답변을_덮지_않는다() {
        var materials = collector.collectBySubQuestion(new ChatContext(null, "가입 방법, 내 요금, 근처 매장", null), List.of(
                new SubQuestionFaqs("가입 방법", List.of(faq(1, Intent.GENERAL)), "가입 방법"),
                new SubQuestionFaqs("내 요금", List.of(faq(2, Intent.USER_DATA)), "내 요금"),
                new SubQuestionFaqs("근처 매장", List.of(faq(3, Intent.STORE_DATA)), "근처 매장")));
        when(llm.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            ((StoreSearchRecorder) request.toolContext().get(StoreTools.RECORDER)).markLocationRequired();
            return new LlmResponseDto("""
                    {"answers":[{"answer":"앱에서 가입할 수 있습니다.","status":"ANSWER"},
                    {"answer":"","status":"ABSTAIN"},{"answer":"","status":"ABSTAIN"}],"evidence_ids":[]}
                    """);
        });

        AiAnswer answer = ai.generateAnswer(materials);

        assertThat(answer.answer()).startsWith("앱에서 가입할 수 있습니다.\n\n")
                .contains("「내 요금」에 대해서는 로그인한 사용자만", "「근처 매장」에 대해서는 기준 위치가 없어")
                .doesNotContain("현재 안내 자료로는");
        assertThat(answer.slotOutcomes()).containsExactly(new AiAnswer.SlotOutcome("ANSWER", false, false),
                new AiAnswer.SlotOutcome("ABSTAIN", false, true), new AiAnswer.SlotOutcome("ABSTAIN", false, true));
    }

    @Test
    void 회선이_없어도_답할_수_있는_프로필_답변은_유지한다() {
        givenNoSubscription();
        AnswerMaterials materials = collector.collect(new ChatContext(7L, "내 이름 알려줘", null),
                List.of(faq(1, Intent.USER_DATA)));
        when(llm.generateAnswer(any())).thenReturn(response("ANSWER", "이름은 홍길동입니다."));

        assertThat(ai.generateAnswer(materials).answer()).isEqualTo("이름은 홍길동입니다.");
    }

    @Test
    void 일반_자료_부족은_다른_질문의_로그인_실패로_바꾸지_않는다() {
        var materials = collector.collectBySubQuestion(new ChatContext(null, "가입 방법과 내 요금", null), List.of(
                new SubQuestionFaqs("가입 방법", List.of(faq(1, Intent.GENERAL)), "가입 방법"),
                new SubQuestionFaqs("내 요금", List.of(faq(2, Intent.USER_DATA)), "내 요금")));
        when(llm.generateAnswer(any())).thenReturn(new LlmResponseDto("""
                {"answers":[{"answer":"","status":"ABSTAIN"},{"answer":"","status":"ABSTAIN"}],"evidence_ids":[]}
                """));

        assertThat(ai.generateAnswer(materials).answer())
                .contains("「가입 방법」에 대해서는 현재 안내 자료로는 확인해 드리기 어렵습니다.",
                        "「내 요금」에 대해서는 로그인한 사용자만");
    }

    @Test
    void 나이를_모르는_것을_회선이_없는_탓으로_잘못_안내하지_않는다() {
        givenNoSubscription();
        AnswerMaterials materials = collector.collect(new ChatContext(7L, "내 나이 알려줘", null),
                List.of(faq(1, Intent.USER_DATA)));
        abstain();

        assertThat(ai.generateAnswer(materials).answer())
                .isEqualTo("문의하신 내용은 현재 안내 자료로는 확인해 드리기 어렵습니다.");
    }

    private void givenNoSubscription() {
        when(summarizer.summarize(7L)).thenReturn(new UserDataSummary(LocalDate.of(2026, 10, 26),
                new UserDataSummary.Profile("홍길동", null, null, null), List.of()));
        when(summarizer.format(any())).thenReturn("이용 중인 회선이 없습니다.");
    }

    private void abstain() {
        when(llm.generateAnswer(any())).thenReturn(response("ABSTAIN", ""));
    }

    private static LlmResponseDto response(String status, String answer) {
        return new LlmResponseDto("{\"answers\":[{\"answer\":\"" + answer
                + "\",\"status\":\"" + status + "\"}],\"evidence_ids\":[]}");
    }

    private static FaqSearchResponseDto faq(long id, Intent intent) {
        return new FaqSearchResponseDto(id, "질문", "앱에서 가입할 수 있습니다.", 0.9, intent);
    }
}
