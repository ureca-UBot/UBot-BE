package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.ai.service.AiService;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.cache.FaqCacheProperties;
import com.ubot.faq.cache.FaqSemanticCacheService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.unanswered.service.UnansweredQuestionService;

@DisplayName("채팅 공식 FAQ 캐시 분기")
class ChatAnswerProcessorSemanticCacheTest {
    private static final String QUESTION = "이심이 어떤 건지 알려줘";
    private static final Runnable NOT_CANCELLED = () -> { };
    private final FaqVectorService vectors = ChatTestFixtures.vectorService();
    private final AiService ai = mock(AiService.class);
    private final ChatContextCollector collector = mock(ChatContextCollector.class);
    private final UnansweredQuestionService unanswered = mock(UnansweredQuestionService.class);
    private final FaqCacheProperties cacheProperties = new FaqCacheProperties();

    @Test
    @DisplayName("HIT은 자료 수집·LLM 없이 공식 답변과 해당 FAQ 한 건만 반환한다")
    void hitSkipsCollectionAndLlm() {
        String officialAnswer = "물리적인 SIM 카드 없이 사용하는 디지털 SIM입니다.\n";
        FaqSearchResponseDto best = new FaqSearchResponseDto(14L, "eSIM이 무엇인가요?", officialAnswer, 0.98,
                Intent.GENERAL, 114L, "이심의 의미", true);
        List<FaqSearchResponseDto> candidates = List.of(best, faq(322, 0.6, false));
        when(vectors.getSimilarList(QUESTION, 3)).thenReturn(candidates);

        ChatAnswerResult result = processor(true, 3).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.isFailed()).isFalse();
        assertThat(result.answer().answer()).isEqualTo(officialAnswer);
        assertThat(result.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(14L);
        verify(vectors).getSimilarList(QUESTION, 3);
        verify(vectors).searchWithVector(QUESTION, null, 3);
        verifyNoMoreInteractions(vectors);
        verifyNoInteractions(collector, unanswered);
        verify(ai).validateCachedAnswer(any(), eq(best.answer()), eq(String.valueOf(best.faqId())));
        verify(ai, never()).generateAnswer(any());
    }

    @Test
    @DisplayName("MISS는 검색을 반복하지 않고 벡터 검색 순서로 LLM에 전달한다")
    void missReusesDenseSearchOrder() {
        when(vectors.getSimilarList(QUESTION, 3)).thenReturn(List.of(faq(14, 0.94, true), faq(322, 0.8, false)));
        prepareLlm();

        ChatAnswerResult result = processor(true, 3).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.answer().answer()).isEqualTo("LLM 답변");
        assertThat(result.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(14L, 322L);
        verify(vectors).getSimilarList(QUESTION, 3);
        verify(vectors).searchWithVector(QUESTION, null, 3);
        verifyNoMoreInteractions(vectors);
        verify(ai).generateAnswer(any());
    }

    @Test
    @DisplayName("OFF는 높은 점수와 승인 여부에 관계없이 기존 LLM 처리를 유지한다")
    void disabledKeepsExistingBehavior() {
        when(vectors.getSimilarList(QUESTION, 3)).thenReturn(List.of(faq(14, 1, true), faq(322, 0.6, false)));
        prepareLlm();

        ChatAnswerResult result = processor(false, 3).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.answer().answer()).isEqualTo("LLM 답변");
        verify(vectors).getSimilarList(QUESTION, 3);
    }

    @Test
    @DisplayName("LLM 유사도 필터 전의 2위까지 비교하며 추가 후보가 LLM 개수를 늘리지 않는다")
    void topOneStillChecksRunnerUpBeforeConfidenceFilter() {
        // 2위는 LLM 기준 0.75 미만이어도 캐시 점수 차이에는 포함되어야 합니다.
        when(vectors.getSimilarList(QUESTION, 2)).thenReturn(List.of(faq(14, 0.96, true), faq(322, 0.74, false)));
        cacheProperties.setMinMargin(0.3);
        prepareLlm();

        ChatAnswerResult result = processor(true, 1).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.answer().answer()).isEqualTo("LLM 답변");
        assertThat(result.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(14L);
        verify(vectors).getSimilarList(QUESTION, 2);
        verify(vectors).searchWithVector(QUESTION, null, 2);
        verifyNoMoreInteractions(vectors);
    }

    @Test
    @DisplayName("점수 차이로 MISS하면 원래 후보를 제거하지 않고 기존 RAG에 전달한다")
    void marginMissPreservesOriginalCandidates() {
        List<FaqSearchResponseDto> candidates = List.of(faq(14, 0.98, true), faq(322, 0.90, false));
        int candidateK = 3;
        when(vectors.getSimilarList(QUESTION, candidateK)).thenReturn(candidates);
        prepareLlm();

        ChatAnswerResult result = processor(true, 3).generateAnswer(attempt(), null, NOT_CANCELLED);
        List<FaqSearchResponseDto> expected = candidates;

        assertThat(result.answer().answer()).isEqualTo("LLM 답변");
        assertThat(result.faqs()).containsExactlyElementsOf(expected);
        ArgumentCaptor<AnswerMaterials> materials = ArgumentCaptor.forClass(AnswerMaterials.class);
        verify(ai).generateAnswer(materials.capture());
        assertThat(materials.getValue().faqs()).containsExactlyElementsOf(expected);
        verify(vectors).getSimilarList(QUESTION, candidateK);
        verify(vectors).searchWithVector(QUESTION, null, candidateK);
        verifyNoMoreInteractions(vectors);
    }

    @Test
    @DisplayName("캐시 조건과 LLM 유사도 기준 모두 미달이면 기존 미응답 처리를 유지한다")
    void belowThresholdKeepsExistingUnansweredRoute() {
        when(vectors.getSimilarList(QUESTION, 3)).thenReturn(List.of(faq(14, 0.74, true), faq(322, 0.6, false)));

        ChatAnswerResult result = processor(true, 3).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
        verify(vectors).getSimilarList(QUESTION, 3);
        verify(vectors).searchWithVector(QUESTION, null, 3);
        verifyNoMoreInteractions(vectors);
        verifyNoInteractions(ai, collector);
    }

    @Test
    @DisplayName("캐시를 켜도 기존 DB 검색 실패 코드를 유지한다")
    void keepsSearchFailureContract() {
        when(vectors.getSimilarList(QUESTION, 3)).thenThrow(new DataAccessResourceFailureException("검색 실패"));
        assertThat(processor(true, 3).generateAnswer(attempt(), null, NOT_CANCELLED).errorCode())
                .isEqualTo(ChatErrorCode.VECTOR_SEARCH_FAILED);
        verifyNoInteractions(ai, collector);
    }

    @Test
    @DisplayName("캐시 판정 후 취소되면 HIT도 답변으로 확정하지 않는다")
    void cancellationAfterDecisionIsRespected() {
        List<FaqSearchResponseDto> candidates = List.of(faq(14, 0.98, true), faq(322, 0.6, false));
        when(vectors.getSimilarList(QUESTION, 3)).thenReturn(candidates);
        AtomicInteger checks = new AtomicInteger();
        Runnable cancellation = () -> {
            if (checks.incrementAndGet() == 3) {
                throw new CancellationException("취소");
            }
        };
        assertThatThrownBy(() -> processor(true, 3).generateAnswer(attempt(), null, cancellation))
                .isInstanceOf(CancellationException.class);
        verify(vectors).getSimilarList(QUESTION, 3);
        verify(vectors).searchWithVector(QUESTION, null, 3);
        verifyNoMoreInteractions(vectors);
        verifyNoInteractions(ai, collector);
    }

    private ChatAnswerProcessor processor(boolean cacheEnabled, int topK) {
        cacheProperties.setEnabled(cacheEnabled);
        ChatAnswerProcessor processor = ChatTestFixtures.processor(new FaqRetrievalService(vectors), ai, collector, ChatTestFixtures.originalSubQuestions(), new FaqSemanticCacheService(cacheProperties), ChatTestFixtures.passthroughSentenceFilter(), ChatTestFixtures.inputGuard());
        ReflectionTestUtils.setField(processor, "topK", topK);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
        return processor;
    }

    private void prepareLlm() {
        when(collector.collectBySubQuestion(any(), anyList(), anyList())).thenAnswer(invocation -> {
            List<SubQuestionFaqs> subQuestions = invocation.getArgument(1);
            return AnswerMaterials.builder(QUESTION)
                    .addFaqs(subQuestions.stream().flatMap(subQuestion -> subQuestion.faqs().stream()).toList())
                    .subQuestions(subQuestions)
                    .build();
        });
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("LLM 답변", null, false));
    }

    private AnswerAttemptsHistory attempt() {
        return AnswerAttemptsHistory.builder().id(100L).userId(1L).question(QUESTION).attemptCount(1)
                .createdAt(LocalDateTime.now()).status("PENDING").build();
    }

    private FaqSearchResponseDto faq(long id, double score, boolean cacheable) {
        return new FaqSearchResponseDto(id, "FAQ 질문 " + id, "검색 답변", score, Intent.GENERAL,
                id + 100, "유사표현 " + id, cacheable);
    }
}
