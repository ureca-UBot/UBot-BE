package com.ubot.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.service.AiService;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.cache.FaqCacheProperties;
import com.ubot.faq.cache.FaqSemanticCacheService;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.repository.FaqVectorRepository;
import com.ubot.faq.retrieval.FaqRetrievalProperties;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.retrieval.rerank.FaqRerankService;
import com.ubot.faq.retrieval.rerank.RerankerClient;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.unanswered.service.UnansweredQuestionService;

@DisplayName("채팅 공식 FAQ 캐시 분기")
class ChatAnswerProcessorSemanticCacheTest {
    private static final String QUESTION = "이심이 어떤 건지 알려줘";
    private static final Runnable NOT_CANCELLED = () -> { };
    private final FaqVectorService vectors = mock(FaqVectorService.class);
    private final FaqVectorRepository repository = mock(FaqVectorRepository.class);
    private final AiService ai = mock(AiService.class);
    private final ChatContextCollector collector = mock(ChatContextCollector.class);
    private final UnansweredQuestionService unanswered = mock(UnansweredQuestionService.class);
    private final RerankerClient reranker = mock(RerankerClient.class);
    private final FaqCacheProperties cacheProperties = new FaqCacheProperties();

    @Test
    @DisplayName("HIT은 리랭커·자료 수집·LLM 없이 공식 답변과 해당 FAQ 한 건만 반환한다")
    void hitSkipsRerankerAndLlm() {
        when(vectors.getSimilarList(QUESTION, 10)).thenReturn(List.of(faq(14, 0.98, true), faq(322, 0.6, false)));
        String officialAnswer = "물리적인 SIM 카드 없이 사용하는 디지털 SIM입니다.\n";
        when(repository.findCurrentCacheableAnswer(14L, 114L, 1)).thenReturn(Optional.of(officialAnswer));

        ChatAnswerResult result = processor(true, true, 3).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.isFailed()).isFalse();
        assertThat(result.answer().answer()).isEqualTo(officialAnswer);
        assertThat(result.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(14L);
        verify(vectors).getSimilarList(QUESTION, 10);
        verifyNoMoreInteractions(vectors);
        verifyNoInteractions(reranker, ai, collector, unanswered);
    }

    @Test
    @DisplayName("MISS는 검색을 반복하지 않고 기존 리랭커 순서로 LLM에 전달한다")
    void missReusesDenseSearchAndReranks() {
        when(vectors.getSimilarList(QUESTION, 10)).thenReturn(List.of(faq(14, 0.94, true), faq(322, 0.8, false)));
        when(reranker.score(any(), any())).thenReturn(List.of(0.1, 0.9));
        prepareLlm();

        ChatAnswerResult result = processor(true, true, 3).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.answer().answer()).isEqualTo("LLM 답변");
        assertThat(result.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(322L, 14L);
        verify(vectors).getSimilarList(QUESTION, 10);
        verifyNoMoreInteractions(vectors);
        verifyNoInteractions(repository);
        verify(ai).generateAnswer(any());
    }

    @Test
    @DisplayName("OFF는 높은 점수와 승인 여부에 관계없이 기존 LLM 처리를 유지한다")
    void disabledKeepsExistingBehavior() {
        when(vectors.getSimilarList(QUESTION, 3)).thenReturn(List.of(faq(14, 1, true), faq(322, 0.6, false)));
        prepareLlm();

        ChatAnswerResult result = processor(false, false, 3).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.answer().answer()).isEqualTo("LLM 답변");
        verify(vectors).getSimilarList(QUESTION, 3);
        verifyNoInteractions(repository, reranker);
    }

    @Test
    @DisplayName("LLM 유사도 필터 전의 2위까지 비교하며 추가 후보가 LLM 개수를 늘리지 않는다")
    void topOneStillChecksRunnerUpBeforeConfidenceFilter() {
        // 2위는 LLM 기준 0.75 미만이어도 캐시 점수 차이에는 포함되어야 합니다.
        when(vectors.getSimilarList(QUESTION, 2)).thenReturn(List.of(faq(14, 0.96, true), faq(322, 0.74, false)));
        cacheProperties.setMinMargin(0.3);
        prepareLlm();

        ChatAnswerResult result = processor(true, false, 1).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.answer().answer()).isEqualTo("LLM 답변");
        assertThat(result.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(14L);
        verify(vectors).getSimilarList(QUESTION, 2);
        verifyNoMoreInteractions(vectors);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("승인 해제 등으로 재검증에서 탈락하면 이미 검색한 결과로 LLM 처리를 이어간다")
    void invalidatedFaqFallsBackWithoutSecondSearch() {
        when(vectors.getSimilarList(QUESTION, 3)).thenReturn(List.of(faq(14, 0.98, true), faq(322, 0.6, false)));
        when(repository.findCurrentCacheableAnswer(14L, 114L, 1)).thenReturn(Optional.empty());
        prepareLlm();

        ChatAnswerResult result = processor(true, false, 3).generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.answer().answer()).isEqualTo("LLM 답변");
        verify(vectors).getSimilarList(QUESTION, 3);
        verifyNoMoreInteractions(vectors);
    }

    @Test
    @DisplayName("캐시를 켜도 기존 DB 검색 실패 코드를 유지한다")
    void keepsSearchFailureContract() {
        when(vectors.getSimilarList(QUESTION, 3)).thenThrow(new DataAccessResourceFailureException("검색 실패"));
        assertThat(processor(true, false, 3).generateAnswer(attempt(), null, NOT_CANCELLED).errorCode())
                .isEqualTo(ChatErrorCode.VECTOR_SEARCH_FAILED);
        verifyNoInteractions(repository, ai, collector, reranker);
    }

    @Test
    @DisplayName("재검증 후 취소되면 캐시 HIT도 답변으로 확정하지 않는다")
    void cancellationAfterValidationIsRespected() {
        when(vectors.getSimilarList(QUESTION, 3)).thenReturn(List.of(faq(14, 0.98, true), faq(322, 0.6, false)));
        when(repository.findCurrentCacheableAnswer(14L, 114L, 1)).thenReturn(Optional.of("공식 답변"));
        AtomicInteger checks = new AtomicInteger();
        Runnable cancellation = () -> {
            if (checks.incrementAndGet() == 3) {
                throw new CancellationException("취소");
            }
        };
        assertThatThrownBy(() -> processor(true, false, 3).generateAnswer(attempt(), null, cancellation))
                .isInstanceOf(CancellationException.class);
        verify(repository).findCurrentCacheableAnswer(14L, 114L, 1);
        verifyNoInteractions(ai, collector, reranker);
    }

    private ChatAnswerProcessor processor(boolean cacheEnabled, boolean rerankEnabled, int topK) {
        cacheProperties.setEnabled(cacheEnabled);
        FaqRetrievalProperties retrievalProperties = new FaqRetrievalProperties();
        retrievalProperties.getRerank().setEnabled(rerankEnabled);
        ChatAnswerProcessor processor = new ChatAnswerProcessor(
                new FaqRetrievalService(vectors, new FaqRerankService(reranker), retrievalProperties, topK),
                ai, unanswered, collector, new FaqSemanticCacheService(cacheProperties, repository));
        ReflectionTestUtils.setField(processor, "topK", topK);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
        return processor;
    }

    private void prepareLlm() {
        when(collector.collect(any(), any())).thenReturn(AnswerMaterials.builder(QUESTION).build());
        when(ai.generateAnswer(any())).thenReturn(new AiAnswer("LLM 답변", null, false));
    }

    private AnswerAttemptsHistory attempt() {
        return AnswerAttemptsHistory.builder().id(100L).userId(1L).question(QUESTION).attemptCount(1)
                .createdAt(LocalDateTime.now()).status("PENDING").build();
    }

    private FaqSearchResponseDto faq(long id, double score, boolean cacheable) {
        return new FaqSearchResponseDto(id, "FAQ 질문 " + id, "검색 답변", score, Intent.GENERAL,
                id + 100, "유사표현 " + id, cacheable, 1);
    }
}
