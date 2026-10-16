package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
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
import com.ubot.unanswered.service.UnansweredQuestionService;

@DisplayName("채팅 QUESTION_ANSWER 문장 필터 분기")
class ChatAnswerProcessorQuestionAnswerTest {
    private static final String QUESTION = "가입 방법";
    private static final Runnable NOT_CANCELLED = () -> { };

    private final FaqRetrievalService retrieval = mock(FaqRetrievalService.class);
    private final FaqSentenceFilterService sentenceFilter = mock(FaqSentenceFilterService.class);
    private final AiService ai = mock(AiService.class);
    private final UnansweredQuestionService unanswered = mock(UnansweredQuestionService.class);

    @Test
    @DisplayName("캐시 실패 후 상위 k개만 문장 필터에 보내고 LLM에는 원본 질문과 통과 문장만 전달한다")
    void sendsOnlyMatchedSentencesToLlmAndKeepsOriginalFaqAsEvidence() {
        FaqSearchResponseDto first = faq(1L, 0.6, false);
        FaqSearchResponseDto second = faq(2L, 0.5, false);
        FaqSearchResponseDto third = faq(3L, 0.4, false);
        List<FaqSearchResponseDto> candidates = List.of(first, second, third);
        when(retrieval.searchCandidates(QUESTION, null, 2, 0)).thenReturn(new DenseFaqCandidates(candidates, 5));
        when(retrieval.isQuestionAnswerMode()).thenReturn(true);
        when(sentenceFilter.filter(QUESTION, List.of(first, second)))
                .thenReturn(List.of(new MatchedFaq(second, "통과 문장.")));
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("LLM 답변", null, false));

        ChatAnswerResult result = processor(false).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.answer().answer()).isEqualTo("LLM 답변");
        assertThat(result.faqs()).containsExactly(second);
        ArgumentCaptor<AnswerMaterials> materials = ArgumentCaptor.captor();
        verify(ai).generateAnswer(materials.capture());
        assertThat(materials.getValue().faqs()).hasSize(1);
        assertThat(materials.getValue().faqs().getFirst().question()).isEqualTo(second.question());
        assertThat(materials.getValue().faqs().getFirst().answer()).isEqualTo("통과 문장.");
        verify(sentenceFilter).filter(QUESTION, List.of(first, second));
        verify(retrieval, never()).rankCandidates(any(), any(), anyInt());
    }

    @Test
    @DisplayName("통과 문장이 없으면 LLM을 호출하지 않고 미응답으로 처리한다")
    void noPassingSentenceReturnsInsufficientFaq() {
        FaqSearchResponseDto first = faq(1L, 0.6, false);
        when(retrieval.searchCandidates(QUESTION, null, 2, 0))
                .thenReturn(new DenseFaqCandidates(List.of(first), 5));
        when(retrieval.isQuestionAnswerMode()).thenReturn(true);
        when(sentenceFilter.filter(QUESTION, List.of(first))).thenReturn(List.of());

        ChatAnswerResult result = processor(false).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
        verifyNoInteractions(ai);
        verify(unanswered).createUnansweredQuestion(100L, QUESTION,
                com.ubot.unanswered.enums.UnansweredReason.INSUFFICIENT_FAQ, 1L, 0.6);
    }

    @Test
    @DisplayName("공식 FAQ 캐시에 적중하면 문장 필터와 LLM을 건너뛴다")
    void cacheHitSkipsSentenceFiltering() {
        FaqSearchResponseDto official = faq(1L, 0.99, true);
        when(retrieval.searchCandidates(QUESTION, null, 2, 2))
                .thenReturn(new DenseFaqCandidates(List.of(official, faq(2L, 0.6, false)), 5));

        ChatAnswerResult result = processor(true).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.answer().answer()).isEqualTo(official.answer());
        assertThat(result.faqs()).containsExactly(official);
        verifyNoInteractions(sentenceFilter, ai);
    }

    private ChatAnswerProcessor processor(boolean cacheEnabled) {
        FaqCacheProperties properties = new FaqCacheProperties();
        properties.setEnabled(cacheEnabled);
        ChatAnswerProcessor processor = new ChatAnswerProcessor(retrieval, ai, unanswered,
                ChatTestFixtures.collector(), new FaqSemanticCacheService(properties));
        ReflectionTestUtils.setField(processor, "topK", 2);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
        ReflectionTestUtils.setField(processor, "faqSentenceFilterService", sentenceFilter);
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
