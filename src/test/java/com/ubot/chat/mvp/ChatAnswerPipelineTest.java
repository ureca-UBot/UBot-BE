package com.ubot.chat.mvp;

import com.ubot.ai.service.AiService;
import com.ubot.chat.mvp.config.ChatMvpSettings;
import com.ubot.chat.mvp.domain.ChatFailure;
import com.ubot.chat.mvp.service.ChatAnswerPipeline;
import com.ubot.embedding.exception.EmbeddingErrorCode;
import com.ubot.embedding.exception.EmbeddingException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.exception.PromptException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatAnswerPipelineTest {
    private final FaqVectorService search = mock(FaqVectorService.class);
    private final AiService ai = mock(AiService.class);
    private final ChatMvpSettings settings = new ChatMvpSettings(Duration.ofSeconds(150), 4, 16, 3, .75, "llm", "embedding");
    private final ChatAnswerPipeline pipeline = new ChatAnswerPipeline(search, ai, settings);
    private final List<FaqSearchResponseDto> sources = List.of(new FaqSearchResponseDto(10L, "FAQ 질문", "원본 FAQ", .91));

    @Test void returnsGeneratedAnswerAndPassesOnlyCurrentQuestionAndRetrievedFaqs() {
        when(search.getSimilarList("현재 질문", 3)).thenReturn(sources);
        when(ai.generateAnswer("현재 질문", sources)).thenReturn(new LlmResponseDto("생성한 최종 답변"));
        var result = pipeline.generate("현재 질문");
        assertThat(result.answer()).isEqualTo("생성한 최종 답변");
        assertThat(result.sources()).isEqualTo(sources);
        assertThat(result.failure()).isNull();
        verify(ai).generateAnswer("현재 질문", sources);
        verifyNoMoreInteractions(ai);
    }

    @Test void embeddingFailureDoesNotCallLlmAndCanBeRetried() {
        when(search.getSimilarList(anyString(), anyInt())).thenThrow(new EmbeddingException(EmbeddingErrorCode.EMBEDDING_TIMEOUT));
        var result = pipeline.generate("질문");
        assertThat(result.failure().code()).isEqualTo("EM-003");
        assertThat(ChatFailure.isRetryable(result.failure().code())).isTrue();
        verifyNoInteractions(ai);
    }

    @Test void vectorDbFailureDoesNotExposeInternalError() {
        when(search.getSimilarList(anyString(), anyInt())).thenThrow(new DataAccessResourceFailureException("internal connection data"));
        var result = pipeline.generate("질문");
        assertThat(result.failure().code()).isEqualTo("CHAT_VECTOR_UNAVAILABLE");
        assertThat(result.failure().message()).doesNotContain("internal");
        verifyNoInteractions(ai);
    }

    @Test void emptySearchIsNotAReasonToCallLlmRepeatedly() {
        when(search.getSimilarList(anyString(), anyInt())).thenReturn(List.of());
        var result = pipeline.generate("질문");
        assertThat(ChatFailure.isRetryable(result.failure().code())).isFalse();
        verifyNoInteractions(ai);
    }

    @Test void insufficientEvidenceDoesNotCallLlm() {
        when(search.getSimilarList(anyString(), anyInt())).thenReturn(
                List.of(new FaqSearchResponseDto(1L, "질문", "답변", .2)));
        assertThat(pipeline.generate("질문").failure().code()).isEqualTo("CHAT_INSUFFICIENT_FAQ");
        verifyNoInteractions(ai);
    }

    @Test void missingPromptIsNotRetryableAndDoesNotLeakTemplateDetails() {
        when(search.getSimilarList(anyString(), anyInt())).thenReturn(sources);
        when(ai.generateAnswer(anyString(), anyList())).thenThrow(new PromptException("private template path"));
        var result = pipeline.generate("질문");
        assertThat(result.failure().code()).isEqualTo("CHAT_PROMPT_NOT_READY");
        assertThat(result.failure().message()).doesNotContain("private");
        assertThat(ChatFailure.isRetryable(result.failure().code())).isFalse();
    }

    @Test void llmTimeoutIsRetryable() {
        when(search.getSimilarList(anyString(), anyInt())).thenReturn(sources);
        when(ai.generateAnswer(anyString(), anyList())).thenThrow(new LlmException(LlmErrorCode.LLM_TIMEOUT));
        assertThat(ChatFailure.isRetryable(pipeline.generate("질문").failure().code())).isTrue();
    }

    @Test void unconfiguredModelIsNotRetryable() {
        when(search.getSimilarList(anyString(), anyInt())).thenReturn(sources);
        when(ai.generateAnswer(anyString(), anyList())).thenThrow(new LlmException(LlmErrorCode.LLM_MODEL_NOT_CONFIGURED));
        assertThat(ChatFailure.isRetryable(pipeline.generate("질문").failure().code())).isFalse();
    }

    @Test void blankAnswerIsNeverReportedAsSuccess() {
        when(search.getSimilarList(anyString(), anyInt())).thenReturn(sources);
        when(ai.generateAnswer(anyString(), anyList())).thenReturn(new LlmResponseDto(" "));
        assertThat(pipeline.generate("질문").failure().code()).isEqualTo("LLM_RESPONSE_INVALID");
    }
}
