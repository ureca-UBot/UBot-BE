package com.ubot.prompt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.dto.request.LlmRequestDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 금칙어 캐시·매칭·프롬프트 조립만 검사합니다. DB·모델 호출은 하지 않습니다. */
class AiGuardPromptServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ForbiddenWordRepository repository = mock(ForbiddenWordRepository.class);

    @Test
    void inputIncludesOnlyWordsFoundInTheOriginalCurrentQuestionOrHistory() {
        var prompts = prompts(Set.of("원문표현", "정리표현", "과거질문표현", "과거답변표현", "무관등록어"));
        var history = List.of(new ConversationTurn("과거질문표현 문의", "과거답변표현 안내"));

        var request = prompts.input("원문표현 문의", "정리표현 문의", history);
        var data = data(request);

        assertThat(words(data)).containsExactlyInAnyOrder("원문표현", "정리표현", "과거질문표현", "과거답변표현");
        assertThat(data.get("original_question").asString()).isEqualTo("원문표현 문의");
        assertThat(data.get("current_question").asString()).isEqualTo("정리표현 문의");
        assertThat(data.get("history").get(0).get("answer").asString()).isEqualTo(history.getFirst().answer());
        assertThat(request.generationOptions().schemaName()).isEqualTo("input_safety_and_planning");
        verifyNoInteractions(repository);
    }

    @Test
    void outputConsidersTheAnswerAndAllProvidedContextWithoutRewritingThem() {
        var catalog = Set.of("답변표현", "질문표현", "FAQ표현", "자료표현", "세부질문표현", "장소표현", "과거표현", "무관등록어");
        var prompts = prompts(catalog);
        var faq = new FaqSearchResponseDto(1L, "FAQ표현 문의", "FAQ표현 설명", .9, Intent.GENERAL);
        var materials = AnswerMaterials.builder("질문표현 문의")
                .addFaqs(List.of(faq)).addSection(new ContextSection("참고 자료", "자료표현 안내"))
                .subQuestions(List.of(new SubQuestionFaqs("세부질문표현", List.of(faq), "사용자 문의")))
                .build().withHistory(List.of(new ConversationTurn("과거표현 문의", "정상 답변")));
        var stores = new StoreMapResult(null, "장소표현", 1, List.of());

        var request = prompts.output("답변표현 안내", List.of("1"), materials, stores);
        var data = data(request);

        assertThat(words(data)).containsExactlyInAnyOrder("답변표현", "질문표현", "FAQ표현", "자료표현", "세부질문표현", "장소표현", "과거표현");
        assertThat(data.get("candidate_answer").asString()).isEqualTo("답변표현 안내");
        assertThat(data.get("provided_faqs").get(0).get("answer").asString()).isEqualTo(faq.answer());
        assertThat(data.get("execution_results").get(0).get("result").get("placeName").asString()).isEqualTo("장소표현");
        assertThat(data.get("evidence_ids").get(0).asString()).isEqualTo("1");
        assertThat(request.generationOptions().schemaName()).isEqualTo("whole_answer_guard");
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource({"ＢＡＤ,bad", "B\u200Ba\u2060D,bad", "b a d,bad", "바 보입니다.,바보", "바\u200B보,바보", "'바\n보',바보"})
    void promptSelectionUsesTheExistingNormalizationAndSpacedVariantRules(String text, String word) {
        var request = prompts(Set.of("bad", "바보", "무관등록어")).input(text, text, List.of());

        assertThat(words(data(request))).containsExactly(word);
        assertThat(data(request).get("original_question").asString()).isEqualTo(text);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BADGE 아이콘", "Bad Request 오류", "HTTP 400 Bad Request", "상단 바 보기 설정"})
    void normalWordsAndHttpErrorNamesDoNotAddUnrelatedForbiddenWords(String text) {
        var request = prompts(Set.of("bad", "바보")).input(text, text, List.of());

        assertThat(words(data(request))).isEmpty();
    }

    @Test
    void jsonFieldNamesEscapesAndSeparateFieldsDoNotCreateMatches() {
        var request = prompts(Set.of("original_question", "current_question", "history", "n", "바보"))
                .input("바\n", "보", List.of());

        assertThat(words(data(request))).isEmpty();
        assertThat(data(request).get("original_question").asString()).isEqualTo("바\n");
    }

    @Test
    void quotesAndReportsRemainIntactAndSelectionDoesNotMakeASafetyDecision() {
        String answer = "‘바보’라는 모욕을 받은 피해는 신고할 수 있습니다.";
        var request = prompts(Set.of("바보", "무관등록어"))
                .output(answer, List.of(), AnswerMaterials.builder("피해 신고 방법").build(), null);

        assertThat(words(data(request))).containsExactly("바보");
        assertThat(data(request).get("candidate_answer").asString()).isEqualTo(answer);
        assertThat(data(request).get("decision")).isNull();
        assertThat(request.messages().getFirst().content()).contains("피해 신고 안내·설명 목적 인용");
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void addingThreeThousandUnrelatedWordsDoesNotIncreaseEitherPrompt(boolean input) {
        var small = prompts(Set.of("바보"));
        var catalog = new HashSet<String>();
        catalog.add("바보");
        for (int index = 0; index < 3000; index++) catalog.add("무관등록어" + index + "끝");
        var large = prompts(catalog);
        var materials = AnswerMaterials.builder("문의").build();
        var expected = input ? small.input("바보", "바보", List.of())
                : small.output("바보", List.of(), materials, null);
        var actual = input ? large.input("바보", "바보", List.of())
                : large.output("바보", List.of(), materials, null);

        assertThat(actual.messages()).isEqualTo(expected.messages());
        assertThat(actual.responseSchema()).isEqualTo(expected.responseSchema());
        assertThat(actual.generationOptions()).isEqualTo(expected.generationOptions());
        verifyNoInteractions(repository);
    }

    private AiGuardPromptService prompts(Set<String> catalog) {
        return new AiGuardPromptService(new ForbiddenWordFilterService(repository).preview(catalog));
    }

    private static JsonNode data(LlmRequestDto request) {
        return JSON.readTree(request.messages().getLast().content());
    }

    private static List<String> words(JsonNode data) {
        List<String> values = new ArrayList<>();
        for (JsonNode word : data.get("forbidden_words")) values.add(word.asString());
        return values;
    }
}
