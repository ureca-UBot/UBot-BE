package com.ubot.chat.mvp.service;

import com.ubot.ai.service.AiService;
import com.ubot.chat.mvp.config.ChatMvpSettings;
import com.ubot.chat.mvp.domain.ChatFailure;
import com.ubot.embedding.exception.EmbeddingException;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.service.FaqVectorService;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.exception.PromptException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Calls the existing team services without altering their implementations or using chat memory. */
@Service
@RequiredArgsConstructor
public class ChatAnswerPipeline {
    private final FaqVectorService faqVectorService;
    private final AiService aiService;
    private final ChatMvpSettings settings;

    public record Result(String answer, List<FaqSearchResponseDto> sources, ChatFailure failure) {
        public static Result failed(String code, String message) {
            return new Result(null, List.of(), new ChatFailure(code, message));
        }
    }

    public Result generate(String question) {
        List<FaqSearchResponseDto> results;
        try {
            results = faqVectorService.getSimilarList(question, settings.topK());
        } catch (EmbeddingException exception) {
            return Result.failed(exception.getErrorCode().getCode(), exception.getErrorCode().getMessage());
        } catch (DataAccessException exception) {
            return Result.failed("CHAT_VECTOR_UNAVAILABLE", "FAQ 검색 저장소에 연결할 수 없습니다.");
        }
        if (results == null || results.isEmpty()) {
            return Result.failed("CHAT_NO_FAQ", "질문에 관련된 FAQ를 찾지 못했습니다.");
        }
        double bestScore = results.stream().mapToDouble(FaqSearchResponseDto::similarityScore)
                .filter(Double::isFinite).max().orElse(-1);
        if (bestScore < settings.confidenceThreshold()) {
            return Result.failed("CHAT_INSUFFICIENT_FAQ", "정확한 답변을 만들 근거가 부족합니다.");
        }
        try {
            var response = aiService.generateAnswer(question, results);
            if (response == null || !StringUtils.hasText(response.answer())) {
                return Result.failed("LLM_RESPONSE_INVALID", "LLM 서버에서 올바른 답변을 받지 못했습니다.");
            }
            return new Result(response.answer(), List.copyOf(results), null);
        } catch (PromptException exception) {
            return Result.failed("CHAT_PROMPT_NOT_READY", "답변 프롬프트가 준비되지 않았습니다.");
        } catch (LlmException exception) {
            return Result.failed(exception.getErrorCode().name(), exception.getErrorCode().getMessage());
        }
    }
}
