package com.ubot.prompt.service;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ConversationTurn;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.prompt.exception.PromptErrorCode;
import com.ubot.prompt.exception.PromptException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 검증된 실험 원본을 읽고, 실제 질문·근거·조회 결과만 JSON 자료로 넣습니다. */
@Service
public class AiGuardPromptService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ForbiddenWordFilterService words;

    public AiGuardPromptService(ForbiddenWordFilterService words) {
        this.words = words;
    }

    public LlmRequestDto input(String original, String current, List<ConversationTurn> history, boolean planning) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("original_question", original);
        data.put("current_question", current);
        data.put("history", history);
        data.put("forbidden_words", words.getActiveForbiddenWords());
        return request(planning ? "input-integrated" : "input-safety", data,
                planning ? "input_safety_and_planning" : "input_safety", planning ? 512 : 128);
    }

    public LlmRequestDto output(String answer, List<String> evidenceIds, AnswerMaterials materials, StoreMapResult stores) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", materials.question());
        data.put("candidate_answer", answer);
        data.put("provided_faqs", materials.faqs());
        data.put("additional_evidence", materials.sections());
        data.put("sub_questions", materials.subQuestions());
        data.put("history", materials.history());
        data.put("store_results", stores);
        List<Map<String, Object>> executed = new ArrayList<>();
        if (stores != null) executed.add(Map.of("operation", "STORE_SEARCH", "result", stores));
        if (materials.toolRecords() != null) {
            executed.add(Map.of("operation", "USER_DATA_LOOKUP", "result", materials.toolRecords()));
        }
        data.put("execution_results", executed);
        data.put("evidence_ids", evidenceIds == null ? List.of() : evidenceIds);
        data.put("forbidden_words", words.getActiveForbiddenWords());
        return request("output-safety", data, "whole_answer_guard", 320);
    }

    private LlmRequestDto request(String name, Map<String, Object> data, String schemaName, int maxTokens) {
        String system = read(name + "-system.txt");
        Map<String, Object> schema;
        try {
            schema = JSON.readValue(read(name + "-schema.json"), new TypeReference<>() { });
        } catch (RuntimeException exception) {
            throw new PromptException(PromptErrorCode.PROMPT_NOT_READY);
        }
        return new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.SYSTEM, system),
                new LlmMessageRequestDto(LlmMessageRole.USER, JSON.writeValueAsString(data))))
                .withResponseSchema(schema).withGenerationOptions(schemaName, maxTokens, true);
    }

    private String read(String name) {
        try (var input = new ClassPathResource("prompts/ai-guard/" + name).getInputStream()) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) throw new PromptException(PromptErrorCode.PROMPT_NOT_READY);
            return text;
        } catch (IOException exception) {
            throw new PromptException(PromptErrorCode.PROMPT_NOT_READY);
        }
    }
}
