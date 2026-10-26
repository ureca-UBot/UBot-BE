package com.ubot.ai.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.ai.dto.*;
import com.ubot.ai.tool.*;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.forbiddenword.entity.ForbiddenWord;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.guardreview.service.ReviewCaptureService;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.llm.service.LlmService;
import com.ubot.location.service.LocationService;
import com.ubot.prompt.service.PromptService;
import com.ubot.store.service.StoreService;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

/** Real guard and store-tool missing-location branch; model, capture and external services are mocks. */
class StoreLocationGuardConnectionTest {
    private final PromptService prompts = mock(PromptService.class);
    private final LlmService llm = mock(LlmService.class);
    private final ReviewCaptureService capture = mock(ReviewCaptureService.class);
    private final StoreService stores = mock(StoreService.class);
    private final LocationService locations = mock(LocationService.class);
    private final StoreTools storeTools = new StoreTools(new NearbyStoreSearcher(stores, 3, 5), locations);
    private final ForbiddenWordRepository repository = mock(ForbiddenWordRepository.class);
    private final ForbiddenWordFilterService words = new ForbiddenWordFilterService(repository);
    private final AiOutputGuardService guard = new AiOutputGuardService(words);
    private final AiService service = new AiService(prompts, llm, AiToolFixtures.registry(storeTools), guard,
            SubQuestionLabels.withoutMorphology(), org.mockito.Mockito.mock(AiLlmOutputGuardService.class),
            new com.ubot.ai.config.AiOutputGuardProperties(), capture, org.mockito.Mockito.mock(com.ubot.guardreview.service.ReviewCheckService.class));
    private final LlmRequestDto prompt = new LlmRequestDto(
            List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "synthetic request")));
    private final List<FaqSearchResponseDto> faqs = List.of(
            new FaqSearchResponseDto(1L, "월 요금", "월 요금은 55,000원입니다.", 1.0, Intent.GENERAL));

    @BeforeEach
    void setup() {
        when(repository.findAllByStatus(ForbiddenWordStatus.ACTIVE)).thenReturn(
                List.of(ForbiddenWord.builder().word("바보").status(ForbiddenWordStatus.ACTIVE).build()));
        words.refreshForbiddenWordCache();
        when(prompts.createSlotPrompt(anyString(), anyList(), anyList(), anyList(), anyList())).thenReturn(prompt);
        when(capture.captureResponse(any(), eq(17L), anyString())).thenReturn(88L);
    }

    private AiAnswer generate(String output, boolean storeContext, boolean requestLocation, StoreMapResult actual) {
        var builder = AnswerMaterials.builder("근처 매장과 월 요금 안내").addFaqs(faqs);
        if (storeContext) builder.enableTool(AiTool.STORE_SEARCH);
        var materials = builder.build().withReviewAttempt(17L);
        when(llm.generateAnswer(any())).thenAnswer(invocation -> {
            LlmRequestDto request = invocation.getArgument(0);
            if (requestLocation) storeTools.findNearbyStores(null, new ToolContext(request.toolContext()));
            if (actual != null)
                ((StoreSearchRecorder) request.toolContext().get(StoreTools.RECORDER)).record(actual);
            return new LlmResponseDto(output);
        });
        return service.generateAnswer(materials);
    }

    private void assertRejected(String output, boolean storeContext, boolean requestLocation, StoreMapResult actual) {
        assertThatThrownBy(() -> generate(output, storeContext, requestLocation, actual))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LlmErrorCode.LLM_OUTPUT_REJECTED));
        verify(capture).recordGuardDecision(eq(88L), anyString(), eq(ReviewCaptureService.Decision.REJECTED),
                eq(OutputGuardResult.Stage.CODE), argThat(reasons -> !reasons.isEmpty()));
        verify(llm, times(1)).generateAnswer(any());
        verify(llm, never()).generateJudgment(any());
        verifyNoInteractions(stores, locations);
    }

    static Stream<Arguments> locationRequests() {
        return Stream.of("어느 지역인지 알려 주세요.", "위치를 알려 주세요.", "기준이 될 지명을 알려 주세요.",
                        "검색할 지역명을 입력해 주세요.", "역 이름을 말씀해 주세요.", "원하시는 동네 이름을 알려 주세요.",
                        "지역이나 역 이름을 알려 주세요.", "어느 지역인가요?", "기준 위치는 어디인가요?",
                        "매장 검색을 위한 지역명을 알려 주세요.")
                .flatMap(text -> Stream.of(Arguments.of(text, false), Arguments.of(text, true)));
    }

    @ParameterizedTest
    @MethodSource("locationRequests")
    void preservesOnlySearchLocationFieldsBeforeLookup(String text, boolean requestLocation) {
        var answer = generate(text, true, requestLocation, null);
        assertThat(answer.answer()).isEqualTo(text);
        assertThat(answer.storeMap()).isNull();
        assertThat(answer.locationRequired()).isEqualTo(requestLocation);
        assertThat(answer.outputGuardResult().rejected()).isFalse();
        verify(capture).recordGuardResult(eq(88L), eq(text), argThat(result -> !result.rejected()));
        verify(llm, times(1)).generateAnswer(any());
        verifyNoInteractions(stores, locations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"위치를 알려 주세요.", "어느 지역인가요?", "역 이름을 입력해 주세요."})
    void sameInputRequestHasNoExemptionOutsideServerStoreContext(String text) {
        assertRejected(text, false, false, null);
    }

    @Test
    void modelStatusCannotCreateServerStoreContext() {
        String output = JsonMapper.builder().build().writeValueAsString(
                Map.of("answer", "위치를 알려 주세요.", "status", "LOCATION_REQUIRED", "evidence_ids", List.of()));
        assertRejected(output, false, false, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"위치를 알려 주세요.", "지역이나 역 이름을 알려 주세요."})
    void completedLookupDoesNotAllowUnnecessaryLocationRequests(String text) {
        var actual = new StoreMapResult(new Location(37.5, 127), "강남역", 3, List.of());
        assertRejected(text, true, false, actual);
    }

    @Test
    void successfulLookupStillClearsLocationRequiredAndReturnsActualMap() {
        var actual = new StoreMapResult(new Location(37.5, 127), "강남역", 3, List.of());
        var answer = generate("반경 3km 안에 매장이 없습니다.", true, true, actual);
        assertThat(answer.storeMap()).isSameAs(actual);
        assertThat(answer.locationRequired()).isFalse();
        assertThat(answer.outputGuardResult().rejected()).isFalse();
        verifyNoInteractions(stores, locations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"지역과 주민번호를 알려 주세요.", "지역과 비밀번호를 입력해 주세요.",
            "집 주소를 알려 주세요.", "전화번호와 위치를 알려 주세요.", "이름을 알려 주세요.",
            "지역과 비밀\u200B번호를 알려 주세요.", "다른 문의가 있나요?", "지역과 계좌번호를 알려 주세요."})
    void locationContextDoesNotAllowOtherDataOrGenericFollowups(String text) {
        assertRejected(text, true, true, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"위치를 알려 주세요. 고객은 바보입니다.",
            "어느 지역인지 알려 주세요. 환불을 완료했습니다.",
            "역 이름을 알려 주세요. 근처 매장을 조회했습니다.",
            "위치를 알려 주세요. 월 요금은 99,000원입니다.",
            "위치와 비밀번호를 알려 주세요. 월 요금은 55,000원입니다."})
    void anyViolationStillRejectsWholeAnswerWithoutRetryOrSafeRemainder(String output) {
        assertRejected(output, true, true, null);
    }

    @Test
    void validTrailingMetadataIsParsedBeforeLocationGuidanceValidation() {
        var answer = generate("지역을 알려 주세요.\nstatus: PARTIAL\nevidence_ids: 1", true, true, null);
        assertThat(answer.answer()).isEqualTo("지역을 알려 주세요.");
        assertThat(answer.answer()).doesNotContain("status:", "evidence_ids:");
        assertThat(answer.outputGuardResult().rejected()).isFalse();
    }

    @Test
    void unrelatedFactualAnswerDoesNotLoseExistingValidation() {
        var answer = generate("월 요금은 55,000원입니다.", true, true, null);
        assertThat(answer.answer()).isEqualTo("월 요금은 55,000원입니다.");
        assertThat(answer.locationRequired()).isTrue();
        verifyNoInteractions(stores, locations);
    }

    @Test
    void rejectedOutputStillCannotRetryBeforeAttemptLimit() {
        var retries = mock(com.ubot.chat.service.ChatAttemptsService.class, CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(retries, "maxAttempts", 3);
        var attempt = com.ubot.chat.entity.AnswerAttemptsHistory.builder()
                .id(19L).question("근처 매장").attemptCount(1).status("FAIL")
                .errorCode(LlmErrorCode.LLM_OUTPUT_REJECTED).build();
        assertThat(retries.validateRetryAttempt(attempt))
                .contains(com.ubot.chat.exception.ChatErrorCode.RETRY_NOT_ALLOWED);
    }
}
