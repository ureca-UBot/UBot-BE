package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.service.AiService;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.cache.FaqCacheProperties;
import com.ubot.faq.cache.FaqSemanticCacheService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.DenseFaqCandidates;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.faq.retrieval.FaqSentenceFilterService.MatchedFaq;
import com.ubot.faq.retrieval.RetrievalOutcome;
import com.pgvector.PGvector;
import com.ubot.unanswered.dto.UnansweredCandidate;
import com.ubot.unanswered.enums.UnansweredSource;

@DisplayName("채팅 FAQ 질문 벡터와 답변 문장 필터")
class ChatAnswerProcessorSentenceFilterTest {
    private static final String QUESTION = "가입 방법";
    private static final PGvector QUERY_VECTOR = new PGvector(new float[] {1, 0});
    private static final Runnable NOT_CANCELLED = () -> { };

    private final FaqRetrievalService retrieval = mock(FaqRetrievalService.class);
    private final FaqSentenceFilterService sentenceFilter = mock(FaqSentenceFilterService.class);
    private final AiService ai = mock(AiService.class);
    private final List<UnansweredCandidate> unanswered = new ArrayList<>();

    @Test
    @DisplayName("캐시 실패 후 상위 k개만 문장 필터에 보내고 LLM에는 원본 질문과 통과 문장만 전달한다")
    void sendsOnlyMatchedSentencesToLlmAndKeepsOriginalFaqAsEvidence() {
        FaqSearchResponseDto first = faq(1L, 0.9, false);
        FaqSearchResponseDto second = faq(2L, 0.8, false);
        FaqSearchResponseDto third = faq(3L, 0.4, false);
        List<FaqSearchResponseDto> candidates = List.of(first, second, third);
        DenseFaqCandidates dense = new DenseFaqCandidates(candidates, 5, QUERY_VECTOR);
        when(retrieval.searchCandidates(QUESTION, null, 2, 0)).thenReturn(dense);
        when(retrieval.rankCandidates(QUESTION, dense, 2))
                .thenReturn(new RetrievalOutcome(candidates, first, false, null));
        when(sentenceFilter.filter(QUERY_VECTOR, List.of(first, second)))
                .thenReturn(List.of(new MatchedFaq(second, "통과 문장.")));
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("LLM 답변", null, false));

        ChatAnswerResult result = processor(false).generateAnswer(attempt(), null, NOT_CANCELLED, unanswered);

        assertThat(result.answer().answer()).isEqualTo("LLM 답변");
        assertThat(result.faqs()).containsExactly(second);
        ArgumentCaptor<AnswerMaterials> materials = ArgumentCaptor.captor();
        verify(ai).generateAnswer(materials.capture());
        assertThat(materials.getValue().faqs()).hasSize(1);
        assertThat(materials.getValue().faqs().getFirst().question()).isEqualTo(second.question());
        assertThat(materials.getValue().faqs().getFirst().answer()).isEqualTo("통과 문장.");
        verify(sentenceFilter).filter(QUERY_VECTOR, List.of(first, second));
        verify(retrieval).rankCandidates(QUESTION, dense, 2);
    }

    @Test
    @DisplayName("통과 문장이 없으면 LLM을 호출하지 않고 미응답으로 처리한다")
    void noPassingSentenceReturnsInsufficientFaq() {
        FaqSearchResponseDto first = faq(1L, 0.8, false);
        DenseFaqCandidates dense = new DenseFaqCandidates(List.of(first), 5, QUERY_VECTOR);
        when(retrieval.searchCandidates(QUESTION, null, 2, 0))
                .thenReturn(dense);
        when(retrieval.rankCandidates(QUESTION, dense, 2))
                .thenReturn(new RetrievalOutcome(List.of(first), first, false, null));
        when(sentenceFilter.filter(QUERY_VECTOR, List.of(first))).thenReturn(List.of());

        ChatAnswerResult result = processor(false).generateAnswer(attempt(), null, NOT_CANCELLED, unanswered);

        assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
        verifyNoInteractions(ai);
        assertThat(unanswered).contains(new UnansweredCandidate(
				1, QUESTION, QUESTION, com.ubot.unanswered.enums.UnansweredReason.INSUFFICIENT_FAQ, 1L, 0.8, UnansweredSource.RETRIEVAL));
    }

    @Test
    @DisplayName("공식 FAQ 캐시에 적중하면 문장 필터와 LLM을 건너뛴다")
    void cacheHitSkipsSentenceFiltering() {
        FaqSearchResponseDto official = faq(1L, 0.99, true);
        when(retrieval.searchCandidates(QUESTION, null, 2, 2))
                .thenReturn(new DenseFaqCandidates(List.of(official, faq(2L, 0.6, false)), 5, QUERY_VECTOR));

        ChatAnswerResult result = processor(true).generateAnswer(attempt(), null, NOT_CANCELLED, unanswered);

        assertThat(result.answer().answer()).isEqualTo(official.answer());
        assertThat(result.faqs()).containsExactly(official);
        verifyNoInteractions(sentenceFilter, ai);
    }

    @Test
    @DisplayName("질문 유사도 미달 FAQ는 답변 문장 필터에 보내지 않는다")
    void filtersQuestionSimilarityBeforeSentenceComparison() {
        FaqSearchResponseDto low = faq(1L, 0.6, false);
        FaqSearchResponseDto passing = faq(2L, 0.75, false);
        DenseFaqCandidates dense = new DenseFaqCandidates(List.of(low, passing), 5, QUERY_VECTOR);
        when(retrieval.searchCandidates(QUESTION, null, 2, 0)).thenReturn(dense);
        when(retrieval.rankCandidates(QUESTION, dense, 2))
                .thenReturn(new RetrievalOutcome(List.of(low, passing), low, false, null));
        when(sentenceFilter.filter(QUERY_VECTOR, List.of(passing)))
                .thenReturn(List.of(new MatchedFaq(passing, "통과 문장.")));
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("LLM 답변", null, false));

        ChatAnswerResult result = processor(false).generateAnswer(attempt(), null, NOT_CANCELLED, unanswered);

        assertThat(result.faqs()).containsExactly(passing);
        verify(sentenceFilter).filter(QUERY_VECTOR, List.of(passing));
    }

    private ChatAnswerProcessor processor(boolean cacheEnabled) {
        FaqCacheProperties properties = new FaqCacheProperties();
        properties.setEnabled(cacheEnabled);
        ChatAnswerProcessor processor = new ChatAnswerProcessor(retrieval, ai,
                ChatTestFixtures.collector(), ChatTestFixtures.originalSubQuestions(), new FaqSemanticCacheService(properties),
                sentenceFilter);
        ReflectionTestUtils.setField(processor, "topK", 2);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
        return processor;
    }

    private AnswerAttemptsHistory attempt() {
        return AnswerAttemptsHistory.builder().id(100L).userId(1L).question(QUESTION).attemptCount(1)
                .createdAt(LocalDateTime.now()).status("PENDING").build();
    }

    private FaqSearchResponseDto faq(Long id, double score, boolean cacheable) {
        return new FaqSearchResponseDto(id, "FAQ 질문 " + id, "원본 답변 " + id, score,
                Intent.GENERAL, id + 100, "유사 질문 " + id, cacheable);
    }
}
