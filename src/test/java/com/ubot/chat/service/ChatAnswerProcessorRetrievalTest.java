package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.service.AiService;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.retrieval.FaqRetrievalService;
import com.ubot.faq.retrieval.FaqSentenceFilterService;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.unanswered.dto.UnansweredCandidate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 벡터 검색 순서, 유사도 기준과 검색 실패 계약을 검증합니다. */
@ExtendWith(MockitoExtension.class)
class ChatAnswerProcessorRetrievalTest {
    private static final Runnable NOT_CANCELLED = () -> {
    };

    FaqVectorService faqVectorService = ChatTestFixtures.vectorService();
    @Mock
    AiService aiService;
    private final List<UnansweredCandidate> unanswered = new ArrayList<>();
    @Mock
    ChatContextCollector chatContextCollector;
    private final FaqSentenceFilterService sentenceFilter = ChatTestFixtures.passthroughSentenceFilter();

    @Test
    @DisplayName("코사인 유사도가 기준 미만이면 전달하지 않는다")
    void belowCosineThresholdIsRejected() {
        when(faqVectorService.getSimilarList("유심 재발급", 3)).thenReturn(List.of(faq(1L, 0.70), faq(2L, 0.60)));
        ChatAnswerProcessor processor = processor();

        ChatAnswerResult result = processor.generateAnswer(attempt(), null, NOT_CANCELLED, unanswered);

        assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
        // 미응답 질문에는 벡터 검색 1위 후보를 남깁니다.
        assertThat(unanswered)
                .extracting(UnansweredCandidate::subQuestionIndex, UnansweredCandidate::subQuestion,
                        UnansweredCandidate::bestFaqId, UnansweredCandidate::bestSimilarity)
                .containsExactly(tuple(1, "유심 재발급", 1L, 0.70));
        verifyNoInteractions(aiService);
        verifyNoInteractions(sentenceFilter);
    }

    @Test
    @DisplayName("벡터 검색 순서와 최대 후보 수를 유지한다")
    void keepsDenseTopK() {
        List<FaqSearchResponseDto> dense = List.of(faq(1L, 0.90), faq(2L, 0.85), faq(3L, 0.80), faq(4L, 0.78));
        when(faqVectorService.getSimilarList("유심 재발급", 3)).thenReturn(dense);
        ChatAnswerProcessor processor = processor();
        answerWith("답변");

        ChatAnswerResult result = processor.generateAnswer(attempt(), null, NOT_CANCELLED, unanswered);

        assertThat(result.isFailed()).isFalse();
        assertThat(result.faqs()).extracting(FaqSearchResponseDto::faqId).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("검색 결과가 없으면 NO_FAQ, 벡터 검색이 실패하면 VECTOR_SEARCH_FAILED다")
    void keepsExistingFailureCodes() {
        when(faqVectorService.getSimilarList("유심 재발급", 3)).thenReturn(List.of());
        assertThat(processor().generateAnswer(attempt(), null, NOT_CANCELLED, unanswered).errorCode())
                .isEqualTo(ChatErrorCode.NO_FAQ);

        when(faqVectorService.getSimilarList("유심 재발급", 3))
                .thenThrow(new DataAccessResourceFailureException("DB 오류"));
        assertThat(processor().generateAnswer(attempt(), null, NOT_CANCELLED, unanswered).errorCode())
                .isEqualTo(ChatErrorCode.VECTOR_SEARCH_FAILED);
    }

    private ChatAnswerProcessor processor() {
        ChatAnswerProcessor processor = ChatTestFixtures.processor(new FaqRetrievalService(faqVectorService), aiService, chatContextCollector, ChatTestFixtures.originalSubQuestions(), ChatTestFixtures.disabledCache(), sentenceFilter, ChatTestFixtures.inputGuard());
        ReflectionTestUtils.setField(processor, "topK", 3);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
        return processor;
    }

    private void answerWith(String answer) {
        lenient().when(chatContextCollector.collectBySubQuestion(any(), any(), any()))
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
