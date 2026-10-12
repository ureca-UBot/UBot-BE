package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.service.AiService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.faq.config.*;
import com.ubot.faq.dto.response.*;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.service.*;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatAnswerProcessorSemanticCacheTest {
    private final FaqVectorService vector = mock(FaqVectorService.class);
    private final FaqCacheService cache = mock(FaqCacheService.class);
    private final AiService ai = mock(AiService.class);
    private final FaqSemanticCacheProperties properties = new FaqSemanticCacheProperties();
    private final FaqCacheProperties cacheProperties = new FaqCacheProperties();
    private final FaqSemanticCacheService semantic = new FaqSemanticCacheService(properties, cacheProperties);
    private final String question = "esim이 어떤 건지 설명해줘.";
    private final FaqSearchResponseDto official = new FaqSearchResponseDto(14L,"eSIM이 무엇인가요?",
            "디지털 SIM입니다.",.98,Intent.GENERAL,true,6);
    private final List<FaqSearchResponseDto> baseline = List.of(
            new FaqSearchResponseDto(1L,"첫 번째 질문","답변",.8,Intent.GENERAL),
            new FaqSearchResponseDto(2L,"두 번째 질문","답변",.79,Intent.GENERAL),
            new FaqSearchResponseDto(3L,"세 번째 질문","답변",.76,Intent.GENERAL));
    private final List<SemanticFaqCandidateResponseDto> candidates = List.of(
            new SemanticFaqCandidateResponseDto(official,question,true),
            new SemanticFaqCandidateResponseDto(baseline.getFirst(),"첫 번째 질문",false));

    @Test void aliasMatchReturnsVerifiedOfficialAnswerWithNoLlm() {
        var processor = processor();
        when(cache.getOrLoadOfficialAnswer(official)).thenReturn(Optional.of("디지털 SIM입니다."));
        var result = processor.generateAnswer(attempt(question),null,()->{});
        assertThat(result.answer().answer()).isEqualTo("디지털 SIM입니다.");
        assertThat(result.faqs()).containsExactly(official);
        verifyNoInteractions(ai);
        verify(vector).searchWithSemanticCache(question,3,"bge-m3:567m");
        verify(vector,never()).getSimilarList(anyString(),anyInt());
    }

    @Test void reviewedScopeReturnsPinnedOfficialAnswerWithoutLlm() {
        var processor = processor();
        properties.setReviewedPolicyEnabled(true);
        var pinned = new FaqSearchResponseDto(14L,"eSIM이 무엇인가요?",
                "물리적인 SIM 카드 없이 사용하는 디지털 SIM입니다.",.93,Intent.GENERAL,true,6);
        when(vector.searchWithSemanticCache(question,3,"bge-m3:567m"))
                .thenReturn(new FaqAnswerSearchResponseDto(baseline,List.of(new SemanticFaqCandidateResponseDto(pinned,question,true),
                        candidates.get(1))));
        when(cache.getOrLoadOfficialAnswer(pinned)).thenReturn(Optional.of(pinned.answer()));
        var result = processor.generateAnswer(attempt(question),null,()->{});
        assertThat(result.answer().answer()).isEqualTo(pinned.answer());
        assertThat(result.faqs()).containsExactly(pinned);
        verifyNoInteractions(ai);
    }

    @Test void reviewedScopeBlocksAdditionalRequestWithoutSemanticLookup() {
        var processor = processor();
        properties.setReviewedPolicyEnabled(true);
        String compound = "실물 유심과 이심을 동시에 사용할 수 있는지 궁금해요. 지원 단말 목록도 부탁드립니다.";
        when(vector.getSimilarList(compound,3)).thenReturn(baseline);
        processor.generateAnswer(attempt(compound),null,()->{});
        verify(vector,never()).searchWithSemanticCache(eq(compound),anyInt(),anyString());
        verifyNoInteractions(cache);
        verify(ai).generateAnswer(ChatTestFixtures.materialsFor(compound,baseline));
    }

    @Test void revokedOrChangedOfficialAnswerKeepsOriginalLlmSources() {
        var processor = processor();
        when(cache.getOrLoadOfficialAnswer(official)).thenReturn(Optional.empty());
        assertThat(processor.generateAnswer(attempt(question),null,()->{}).answer().answer()).isEqualTo("LLM");
        verify(ai).generateAnswer(ChatTestFixtures.materialsFor(question,baseline));
    }

    @Test void disabledCacheKeepsTheBaselineLlmPathWithoutAliasSearch() {
        var processor = processor();
        properties.setEnabled(false);
        when(vector.getSimilarList(question,3)).thenReturn(baseline);
        assertThat(processor.generateAnswer(attempt(question),null,()->{}).answer().answer()).isEqualTo("LLM");
        verifyNoInteractions(cache);
        verify(vector,never()).searchWithSemanticCache(anyString(),anyInt(),anyString());
        verify(ai).generateAnswer(ChatTestFixtures.materialsFor(question,baseline));
    }

    @Test void compoundQuestionUsesBaselineWithoutSemanticLookup() {
        var processor = processor();
        String compound = "esim이 어떤 건지 설명해줘. 그리고 발급 가격도 알려줘.";
        when(vector.getSimilarList(compound,3)).thenReturn(baseline);
        processor.generateAnswer(attempt(compound),null,()->{});
        verify(vector).getSimilarList(compound,3);
        verify(vector,never()).searchWithSemanticCache(eq(compound),anyInt(),anyString());
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

    private ChatAnswerProcessor processor() {
        cacheProperties.setEnabled(true);
        properties.setEnabled(true);
        var processor = new ChatAnswerProcessor(vector,semantic,cache,ai,
                mock(UnansweredQuestionService.class),ChatTestFixtures.collector());
        ReflectionTestUtils.setField(processor,"topK",3);
        ReflectionTestUtils.setField(processor,"confidenceThreshold",.75);
        when(vector.searchWithSemanticCache(question,3,"bge-m3:567m"))
                .thenReturn(new FaqAnswerSearchResponseDto(baseline,candidates));
        when(ai.generateAnswer(any(AnswerMaterials.class))).thenReturn(new AiAnswer("LLM",null,false));
        return processor;
    }

    private AnswerAttemptsHistory attempt(String input) {
        return AnswerAttemptsHistory.builder().id(1L).userId(1L).question(input)
                .idempotencyKey("a".repeat(64)).attemptCount(1).status("PENDING").build();
    }
}
