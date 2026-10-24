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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 검증된 실험 원본을 읽고, 실제 질문·근거·조회 결과만 JSON 자료로 넣습니다. */
@Service
public class AiGuardPromptService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ForbiddenWordFilterService words;

    public AiGuardPromptService(ForbiddenWordFilterService words) {
        this.words = words;
    }

    public LlmRequestDto input(String original, String current, List<ConversationTurn> history) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("original_question", original);
        data.put("current_question", current);
        data.put("history", history);
        return request("input-integrated", data, "input_safety_and_planning", 512);
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
        return request("output-safety", data, "whole_answer_guard", 320);
    }

    private LlmRequestDto request(String name, Map<String, Object> data, String schemaName, int maxTokens) {
        List<String> contents = new ArrayList<>();
        collectTextValues(JSON.valueToTree(data), contents);
        data.put("forbidden_words", words.findMatchingForbiddenWords(contents));
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

    private void collectTextValues(JsonNode node, List<String> contents) {
        if (node.isString()) {
            contents.add(node.asString());
        } else if (node.isContainer()) {
            // JSON 필드명·이스케이프 표기는 검사하지 않고, 실제 문자열 값을 각각 검사합니다.
            for (JsonNode child : node) collectTextValues(child, contents);
        }
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
