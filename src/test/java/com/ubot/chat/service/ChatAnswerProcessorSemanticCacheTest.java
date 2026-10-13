package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.service.AiService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.config.*;
import com.ubot.faq.dto.response.*;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.service.*;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class ChatAnswerProcessorSemanticCacheTest {
    private final FaqVectorService vector = mock(FaqVectorService.class);
    private final FaqCacheService cache = mock(FaqCacheService.class);
    private final AiService ai = mock(AiService.class);
    private final FaqCacheProperties properties = new FaqCacheProperties();
    private final FaqSemanticCacheService semantic = new FaqSemanticCacheService(properties);
    private final String question = "esim이 어떤 건지 설명해줘.";
    private final FaqSearchResponseDto official = new FaqSearchResponseDto(14L,"eSIM이 무엇인가요?",
            "디지털 SIM입니다.",.98,Intent.GENERAL,true,6);
    private final List<FaqSearchResponseDto> baseline = List.of(
            new FaqSearchResponseDto(1L,"첫 번째 질문","답변",.8,Intent.GENERAL),
            new FaqSearchResponseDto(2L,"두 번째 질문","답변",.79,Intent.GENERAL),
            new FaqSearchResponseDto(3L,"세 번째 질문","답변",.76,Intent.GENERAL));
    private final List<SemanticFaqCandidateResponseDto> candidates = List.of(
            new SemanticFaqCandidateResponseDto(official,question),
            new SemanticFaqCandidateResponseDto(baseline.getFirst(),"첫 번째 질문"));

    @Test void aliasMatchReturnsVerifiedOfficialAnswerWithNoLlm() {
        var processor = processor();
        when(cache.getOfficialAnswer(official)).thenReturn(Optional.of("디지털 SIM입니다."));
        var result = processor.generateAnswer(attempt(question),null,()->{});
        assertThat(result.answer().answer()).isEqualTo("디지털 SIM입니다.");
        assertThat(result.faqs()).containsExactly(official);
        verifyNoInteractions(ai);
        verify(vector).searchWithSemanticCache(question,3);
        verify(vector,never()).getSimilarList(anyString(),anyInt());
    }

    @Test void validSemanticCacheReturnsOfficialAnswerWhenBaselineIsEmpty() {
        var processor = processor();
        when(vector.searchWithSemanticCache(question,3))
                .thenReturn(new FaqAnswerSearchResponseDto(List.of(),candidates));
        when(cache.getOfficialAnswer(official)).thenReturn(Optional.of(official.answer()));

        var result = processor.generateAnswer(attempt(question),null,()->{});

        assertThat(result.errorCode()).isNull();
        assertThat(result.answer().answer()).isEqualTo(official.answer());
        assertThat(result.faqs()).containsExactly(official);
        verify(cache).getOfficialAnswer(official);
        verifyNoInteractions(ai);
    }

    @Test void emptyBaselineStillFailsWhenTheSelectedOfficialAnswerIsRevoked() {
        var processor = processor();
        when(vector.searchWithSemanticCache(question,3))
                .thenReturn(new FaqAnswerSearchResponseDto(List.of(),candidates));
        when(cache.getOfficialAnswer(official)).thenReturn(Optional.empty());

        var result = processor.generateAnswer(attempt(question),null,()->{});

        assertThat(result.errorCode()).isEqualTo(ChatErrorCode.NO_FAQ);
        verify(cache).getOfficialAnswer(official);
        verifyNoInteractions(ai);
    }

    @Test void approvedCanonicalQuestionReturnsTheDatabaseAnswerWithoutLlm() {
        var processor = processor();
        String newQuestion = "해지하면 남은 단말기 할부금은 어떻게 되나요?";
        var approved = new FaqSearchResponseDto(48L,newQuestion,
                "통신 서비스를 해지해도 남은 단말기 할부금은 계속 납부해야 합니다.",.98,Intent.GENERAL,true,2);
        when(vector.searchWithSemanticCache(newQuestion,3))
                .thenReturn(new FaqAnswerSearchResponseDto(baseline,List.of(new SemanticFaqCandidateResponseDto(approved,newQuestion),
                        candidates.get(1))));
        when(cache.getOfficialAnswer(approved)).thenReturn(Optional.of(approved.answer()));
        var result = processor.generateAnswer(attempt(newQuestion),null,()->{});
        assertThat(result.answer().answer()).isEqualTo(approved.answer());
        assertThat(result.faqs()).containsExactly(approved);
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "109|모바일 데이터를 추가로 충전하려면 어떤 메뉴에서 신청하는지 알려 주세요.|데이터 추가 충전은 어디에서 신청하나요?|앱의 데이터 충전 메뉴에서 바로 구매할 수 있고 고객센터를 통해서도 신청할 수 있습니다.",
            "246|온라인이 아니라 매장에서도 휴대폰을 구매할 수 있나요?|매장에서도 휴대폰을 살 수 있나요?|매장에서 단말을 구매할 수 있습니다. 앱 온라인 샵은 배송비가 무료입니다."
    }, delimiter = '|')
    void singleIntentQuestionsWithAdditionalOrContrastWordsUseSemanticSearch(
            long faqId, String input, String officialQuestion, String officialAnswer) {
        var processor = processor();
        var approved = new FaqSearchResponseDto(faqId,officialQuestion,officialAnswer,
                .98,Intent.GENERAL,true,2);
        when(vector.searchWithSemanticCache(input,3))
                .thenReturn(new FaqAnswerSearchResponseDto(baseline,List.of(
                        new SemanticFaqCandidateResponseDto(approved,input),candidates.get(1))));
        when(cache.getOfficialAnswer(approved)).thenReturn(Optional.of(officialAnswer));

        var result = processor.generateAnswer(attempt(input),null,()->{});

        assertThat(result.answer().answer()).isEqualTo(officialAnswer);
        assertThat(result.faqs()).containsExactly(approved);
        verify(vector).searchWithSemanticCache(input,3);
        verify(vector,never()).getSimilarList(anyString(),anyInt());
        verifyNoInteractions(ai);
    }

    @Test void additionalRequestOutsideTheFormerPilotUsesBaselineWithoutSemanticLookup() {
        var processor = processor();
        String compound = "해외에서 와이파이만 사용하고 싶어요. 그리고 로밍 요금도 알려줘.";
        when(vector.getSimilarList(compound,3)).thenReturn(baseline);
        processor.generateAnswer(attempt(compound),null,()->{});
        verify(vector,never()).searchWithSemanticCache(eq(compound),anyInt());
        verifyNoInteractions(cache);
        verify(ai).generateAnswer(ChatTestFixtures.materialsFor(compound,baseline));
    }

    @Test void revokedOrChangedOfficialAnswerKeepsOriginalLlmSources() {
        var processor = processor();
        when(cache.getOfficialAnswer(official)).thenReturn(Optional.empty());
        assertThat(processor.generateAnswer(attempt(question),null,()->{}).answer().answer()).isEqualTo("LLM");
        verify(ai).generateAnswer(ChatTestFixtures.materialsFor(question,baseline));
    }

    @Test void disabledCacheKeepsTheBaselineLlmPathWithoutAliasSearch() {
        var processor = processor();
        properties.setEnabled(false);
        when(vector.getSimilarList(question,3)).thenReturn(baseline);
        assertThat(processor.generateAnswer(attempt(question),null,()->{}).answer().answer()).isEqualTo("LLM");
        verifyNoInteractions(cache);
        verify(vector,never()).searchWithSemanticCache(anyString(),anyInt());
        verify(ai).generateAnswer(ChatTestFixtures.materialsFor(question,baseline));
    }

    @Test void compoundQuestionUsesBaselineWithoutSemanticLookup() {
        var processor = processor();
        String compound = "esim이 어떤 건지 설명해줘. 그리고 발급 가격도 알려줘.";
        when(vector.getSimilarList(compound,3)).thenReturn(baseline);
        processor.generateAnswer(attempt(compound),null,()->{});
        verify(vector).getSimilarList(compound,3);
        verify(vector,never()).searchWithSemanticCache(eq(compound),anyInt());
        verifyNoInteractions(cache);
    }

    @Test void cancellationAfterSearchPreventsBothCacheAndLlm() {
        var processor = processor();
        int[] calls = {0};
        assertThatThrownBy(() -> processor.generateAnswer(attempt(question),null,()-> {
            if (++calls[0] == 2) throw new IllegalStateException("cancelled");
        })).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(cache,ai);
    }

    @Test void repeatedPunctuationUsesSemanticSearchButFallbackKeepsTheOriginalQuestion() {
        var processor = processor();
        String raw = "  esim이  뭐야？？？ ";
        when(vector.searchWithSemanticCache(raw,3)).thenReturn(new FaqAnswerSearchResponseDto(baseline,candidates));
        when(cache.getOfficialAnswer(official)).thenReturn(Optional.empty());
        var attempt = attempt(raw);
        assertThat(processor.generateAnswer(attempt,null,()->{}).answer().answer()).isEqualTo("LLM");
        assertThat(attempt.getQuestion()).isEqualTo(raw);
        verify(vector).searchWithSemanticCache(raw,3);
        verify(ai).generateAnswer(ChatTestFixtures.materialsFor(raw,baseline));
    }

    private ChatAnswerProcessor processor() {
        properties.setEnabled(true);
        properties.setSemanticEnabled(true);
        var processor = new ChatAnswerProcessor(vector,semantic,cache,ai,
                mock(UnansweredQuestionService.class),ChatTestFixtures.collector());
        ReflectionTestUtils.setField(processor,"topK",3);
        ReflectionTestUtils.setField(processor,"confidenceThreshold",.75);
        when(vector.searchWithSemanticCache(question,3))
                .thenReturn(new FaqAnswerSearchResponseDto(baseline,candidates));
        when(ai.generateAnswer(any(AnswerMaterials.class))).thenReturn(new AiAnswer("LLM",null,false));
        return processor;
    }

    private AnswerAttemptsHistory attempt(String input) {
        return AnswerAttemptsHistory.builder().id(1L).userId(1L).question(input)
                .idempotencyKey("a".repeat(64)).attemptCount(1).status("PENDING").build();
    }
}
