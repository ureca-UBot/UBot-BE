package com.ubot.chat.service;

import com.ubot.ai.dto.AiAnswer;
import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.service.AiService;
import com.ubot.chat.context.ChatContextCollector;
import com.ubot.chat.entity.AnswerAttemptsHistory;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.unanswered.service.UnansweredQuestionService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 재검색 attempt는 intent 검색을 쓰고, 실패해도 미응답 질문을 남기지 않는지 검증합니다. */
@ExtendWith(MockitoExtension.class)
class ChatAnswerProcessorResearchTest {
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

    ChatAnswerProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ChatAnswerProcessor(
                faqVectorService, aiService, unansweredQuestionService, chatContextCollector);
        ReflectionTestUtils.setField(processor, "topK", 3);
        ReflectionTestUtils.setField(processor, "confidenceThreshold", 0.75);
    }

    @Test
    @DisplayName("재검색 attempt는 선택한 intent로만 검색하고 전체 검색은 하지 않는다")
    void researchSearchesOnlyChosenIntent() {
        AnswerAttemptsHistory research = researchAttempt(Intent.STORE_DATA);
        FaqSearchResponseDto faq = faq(0.9, Intent.STORE_DATA);
        when(faqVectorService.getSimilarListByIntent("유심 재발급", Intent.STORE_DATA, 3)).thenReturn(List.of(faq));
        when(chatContextCollector.collect(any(), any())).thenReturn(AnswerMaterials.builder("유심 재발급").build());
        when(aiService.generateAnswer(any())).thenReturn(new AiAnswer("답변", null, false));

        ChatAnswerResult result = processor.generateAnswer(research, null, NOT_CANCELLED);

        assertThat(result.isFailed()).isFalse();
        assertThat(result.faqs()).containsExactly(faq);
        verify(faqVectorService, never()).getSimilarList(anyString(), anyInt());
    }

    @Test
    @DisplayName("일반 attempt는 기존처럼 전체 FAQ에서 검색한다")
    void regularAttemptSearchesAllFaqs() {
        AnswerAttemptsHistory regular = regularAttempt();
        FaqSearchResponseDto faq = faq(0.9, Intent.GENERAL);
        when(faqVectorService.getSimilarList("유심 재발급", 3)).thenReturn(List.of(faq));
        when(chatContextCollector.collect(any(), any())).thenReturn(AnswerMaterials.builder("유심 재발급").build());
        when(aiService.generateAnswer(any())).thenReturn(new AiAnswer("답변", null, false));

        processor.generateAnswer(regular, null, NOT_CANCELLED);

        verify(faqVectorService, never()).getSimilarListByIntent(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("재검색에서 해당 intent FAQ가 없으면 NO_FAQ로 실패하고 미응답 질문을 저장하지 않는다")
    void researchNoFaqDoesNotCreateUnansweredQuestion() {
        AnswerAttemptsHistory research = researchAttempt(Intent.USER_DATA);
        when(faqVectorService.getSimilarListByIntent(anyString(), eq(Intent.USER_DATA), anyInt()))
                .thenReturn(List.of());

        ChatAnswerResult result = processor.generateAnswer(research, null, NOT_CANCELLED);

        assertThat(result.errorCode()).isEqualTo(ChatErrorCode.NO_FAQ);
        verifyNoInteractions(unansweredQuestionService, aiService);
    }

    @Test
    @DisplayName("재검색 유사도가 기준값 미만이면 INSUFFICIENT_FAQ로 실패하고 미응답 질문을 저장하지 않는다")
    void researchLowSimilarityDoesNotCreateUnansweredQuestion() {
        AnswerAttemptsHistory research = researchAttempt(Intent.STORE_DATA);
        when(faqVectorService.getSimilarListByIntent(anyString(), eq(Intent.STORE_DATA), anyInt()))
                .thenReturn(List.of(faq(0.5, Intent.STORE_DATA)));

        ChatAnswerResult result = processor.generateAnswer(research, null, NOT_CANCELLED);

        assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
        verify(unansweredQuestionService, never())
                .createUnansweredQuestion(anyLong(), anyString(), any(), any(), any());
        verifyNoInteractions(aiService);
    }

    @Test
    @DisplayName("일반 attempt의 FAQ 부족은 기존처럼 미응답 질문을 저장한다")
    void regularLowSimilarityStillCreatesUnansweredQuestion() {
        AnswerAttemptsHistory regular = regularAttempt();
        when(faqVectorService.getSimilarList(anyString(), anyInt()))
                .thenReturn(List.of(faq(0.5, Intent.GENERAL)));

        ChatAnswerResult result = processor.generateAnswer(regular, null, NOT_CANCELLED);

        assertThat(result.errorCode()).isEqualTo(ChatErrorCode.INSUFFICIENT_FAQ);
        verify(unansweredQuestionService).createUnansweredQuestion(
                eq(regular.getId()), eq("유심 재발급"), any(), eq(1L), eq(0.5));
    }

    private AnswerAttemptsHistory regularAttempt() {
        AnswerAttemptsHistory attempt = new AnswerAttemptsHistory(
                1L, "유심 재발급", 1, "key-regular", LocalDateTime.now(), "llm", "embedding");
        ReflectionTestUtils.setField(attempt, "id", 100L);
        return attempt;
    }

    private AnswerAttemptsHistory researchAttempt(Intent intent) {
        AnswerAttemptsHistory source = regularAttempt();
        AnswerAttemptsHistory research = AnswerAttemptsHistory.createResearchAttempt(
                source, intent, "key-research", LocalDateTime.now(), "llm", "embedding");
        ReflectionTestUtils.setField(research, "id", 200L);
        return research;
    }

    private FaqSearchResponseDto faq(double similarity, Intent intent) {
        return new FaqSearchResponseDto(1L, "FAQ 질문", "FAQ 답변", similarity, intent);
    }
}