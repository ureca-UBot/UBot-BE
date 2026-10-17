package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.service.AiService;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.FaqRetrievalProperties;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.retrieval.rerank.FaqRerankService;
import com.ubot.faq.retrieval.rerank.RerankException;
import com.ubot.faq.retrieval.rerank.RerankerClient;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Reranker를 켰을 때 유사도 기준과 최종 개수 제한이 다시 정렬한 후보 전체에 차례로 적용되는지 검증합니다. */
@ExtendWith(MockitoExtension.class)
class ChatAnswerProcessorRerankTest {
    private static final Runnable NOT_CANCELLED = () -> {
    };

    @Mock
    FaqVectorService faqVectorService;
    @Mock
    AiService aiService;
    @Mock
    UnansweredQuestionService unansweredQuestionService;
    @Mock
    ChatContextCollector chatContextCollector;

    @Test
    @DisplayName("다시 정렬한 후보 전체에 코사인 기준을 적용한 뒤 최대 3개로 줄인다")
    void filtersAllRerankedCandidatesThenLimits() {
        // 코사인은 1~4번만 기준(0.75) 이상입니다. Reranker는 6, 5번(기준 미만)을 가장 높게, 이어 4, 3, 2, 1번 순으로 봅니다.
        List<FaqSearchResponseDto> dense = List.of(
                faq(1L, 0.90), faq(2L, 0.85), faq(3L, 0.80), faq(4L, 0.76), faq(5L, 0.70), faq(6L, 0.60));
        when(faqVectorService.getSimilarList("유심 재발급", 10)).thenReturn(dense);
        ChatAnswerProcessor processor = processor((query, documents) -> List.of(0.1, 0.2, 0.3, 0.4, 0.8, 0.9));
        answerWith("답변");

        ChatAnswerResult result = processor.generateAnswer(attempt(), null, NOT_CANCELLED);

        // Reranker 상위 3개(6, 5, 4번)를 먼저 잘랐다면 4번 하나만 남았을 것입니다.
        assertThat(result.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(4L, 3L, 2L);
        ArgumentCaptor<List<FaqSearchResponseDto>> passed = ArgumentCaptor.captor();
        verify(chatContextCollector).collect(any(), passed.capture());
        assertThat(passed.getValue()).extracting(FaqSearchResponseDto::faqId).containsExactly(4L, 3L, 2L);
    }

    @Test
    @DisplayName("Reranker 점수가 높아도 코사인 유사도가 기준 미만이면 전달하지 않는다")
    void rerankScoreDoesNotReplaceCosineThreshold() {
        when(faqVectorService.getSimilarList("유심 재발급", 10)).thenReturn(List.of(faq(1L, 0.70), faq(2L, 0.60)));
        ChatAnswerProcessor processor = processor((query, documents) -> List.of(0.2, 0.99));

        ChatAnswerResult result = processor.generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
        // 미응답 질문에는 Reranker 1위(2번)가 아니라 벡터 검색 1위(1번)를 남깁니다.
        verify(unansweredQuestionService).enqueueUnansweredQuestion(eq(100L), eq("유심 재발급"), any(), eq(1L), eq(0.70));
        verifyNoInteractions(aiService);
    }

    @Test
    @DisplayName("Reranker를 쓰지 못하면 벡터 검색 상위 3개에 코사인 기준을 적용한다")
    void fallsBackToDenseTopK() {
        List<FaqSearchResponseDto> dense = List.of(faq(1L, 0.90), faq(2L, 0.85), faq(3L, 0.80), faq(4L, 0.78));
        when(faqVectorService.getSimilarList("유심 재발급", 10)).thenReturn(dense);
        ChatAnswerProcessor processor = processor((query, documents) -> {
            throw new RerankException("제한 시간 초과");
        });
        answerWith("답변");

        ChatAnswerResult result = processor.generateAnswer(attempt(), null, NOT_CANCELLED);

        assertThat(result.isFailed()).isFalse();
        assertThat(result.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("Reranker를 켜도 검색 결과가 없으면 NO_FAQ, 벡터 검색이 실패하면 VECTOR_SEARCH_FAILED다")
    void keepsExistingFailureCodes() {
        RerankerClient neverCalled = (query, documents) -> {
            throw new AssertionError("호출하면 안 됩니다.");
        };
        when(faqVectorService.getSimilarList("유심 재발급", 10)).thenReturn(List.of());
        assertThat(processor(neverCalled).generateAnswer(attempt(), null, NOT_CANCELLED).errorCode())
                .isEqualTo(ChatErrorCode.NO_FAQ);

        when(faqVectorService.getSimilarList("유심 재발급", 10))
                .thenThrow(new DataAccessResourceFailureException("DB 오류"));
        assertThat(processor(neverCalled).generateAnswer(attempt(), null, NOT_CANCELLED).errorCode())
                .isEqualTo(ChatErrorCode.VECTOR_SEARCH_FAILED);
    }

    private ChatAnswerProcessor processor(RerankerClient rerankerClient) {
        FaqRetrievalProperties properties = new FaqRetrievalProperties();
        properties.getRerank().setEnabled(true);
        ChatAnswerProcessor processor = new ChatAnswerProcessor(
                new FaqRetrievalService(faqVectorService, new FaqRerankService(rerankerClient), properties, 3),
                aiService, unansweredQuestionService, chatContextCollector, ChatTestFixtures.disabledCache());
        ReflectionTestUtils.setField(processor, "topK", 3);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
        return processor;
    }

    private void answerWith(String answer) {
        lenient().when(chatContextCollector.collect(any(), any()))
                .thenReturn(AnswerMaterials.builder("유심 재발급").build());
        lenient().when(aiService.generateAnswer(any())).thenReturn(new AiAnswer(answer, null, false));
    }

    private AnswerAttemptsHistory attempt() {
        AnswerAttemptsHistory attempt = new AnswerAttemptsHistory(
                1L, "유심 재발급", 1, "key-regular", LocalDateTime.now(), "llm", "embedding");
        ReflectionTestUtils.setField(attempt, "id", 100L);
        return attempt;
    }

    private FaqSearchResponseDto faq(Long id, double similarity) {
        return new FaqSearchResponseDto(id, "FAQ 질문 " + id, "FAQ 답변 " + id, similarity, Intent.GENERAL);
    }
}
