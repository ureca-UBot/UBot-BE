package com.ubot.unanswered.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.enums.LlmMessageRole;
import com.ubot.llm.service.LlmService;
import com.ubot.unanswered.repository.UnansweredGroupNamingRepository;
import com.ubot.unanswered.repository.UnansweredGroupNamingRepository.NamingTarget;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
public class UnansweredGroupNamingService {

    static final int BATCH_SIZE = 10;
    static final int QUESTION_LIMIT = 10;
    static final int TOPIC_MAX_LENGTH = 30;

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final Map<String, Object> SCHEMA = createSchema();

    private final UnansweredGroupNamingRepository namingRepository;
    private final LlmService llmService;
    private final Resource systemPromptResource;

    public UnansweredGroupNamingService(
            UnansweredGroupNamingRepository namingRepository,
            LlmService llmService,
            @Value("${prompt.unanswered-naming.system-location:classpath:prompts/unanswered-group-naming-system.txt}")
            Resource systemPromptResource
    ) {
        this.namingRepository = namingRepository;
        this.llmService = llmService;
        this.systemPromptResource = systemPromptResource;
    }

    public int nameGroups() {
        List<NamingTarget> targets = namingRepository.findTargets(BATCH_SIZE);
        if (targets.isEmpty()) {
            return 0;
        }
        String systemPrompt = readSystemPrompt();
        Map<String, Long> categories = namingRepository.findCategoryIdsByName();
        int named = 0;
        for (NamingTarget target : targets) {
            try {
                namingRepository.markAttempted(target.id());
                Optional<Naming> naming = parse(callLlm(systemPrompt, categories, target), categories);
                if (naming.isEmpty()) {
                    log.warn("미응답 묶음 이름을 정하지 못했습니다: 묶음ID={}", target.id());
                    continue;
                }
                if (!namingRepository.saveNaming(target.id(), naming.get().topic(), naming.get().categoryId(),
                        target.questionCount())) {
                    log.info("이름을 정하는 동안 처리가 끝난 묶음이라 저장하지 않았습니다: 묶음ID={}", target.id());
                    continue;
                }
                named++;
            } catch (RuntimeException exception) {
                log.warn("미응답 묶음 이름 생성에 실패했습니다: 묶음ID={}", target.id(), exception);
            }
        }
        log.info("미응답 묶음 이름을 정했습니다: 대상수={}, 성공수={}", targets.size(), named);
        return named;
    }

    private String callLlm(String systemPrompt, Map<String, Long> categories, NamingTarget target) {
        List<String> questions = namingRepository.findRecentQuestions(target.id(), QUESTION_LIMIT);
        StringBuilder user = new StringBuilder()
                .append("카테고리 목록:\n").append(String.join(", ", categories.keySet()))
                .append("\n\n묶음 대표 질문: ").append(target.representativeQuestion())
                .append("\n묶음 질문들:");
        questions.forEach(question -> user.append("\n- ").append(question));
        LlmRequestDto request = new LlmRequestDto(List.of(
                new LlmMessageRequestDto(LlmMessageRole.SYSTEM, systemPrompt),
                new LlmMessageRequestDto(LlmMessageRole.USER, user.toString())))
                .withResponseSchema(SCHEMA);
        LlmResponseDto response = llmService.generateAnswer(request);
        return response.answer();
    }

    static Optional<Naming> parse(String output, Map<String, Long> categories) {
        if (!StringUtils.hasText(output)) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = JSON_MAPPER.readTree(output.strip());
        } catch (JacksonException exception) {
            return Optional.empty();
        }
        JsonNode topicNode = root.get("topic");
        if (topicNode == null || !topicNode.isString()) {
            return Optional.empty();
        }
        String topic = topicNode.asString().strip();
        if (!StringUtils.hasText(topic) || topic.length() > TOPIC_MAX_LENGTH) {
            return Optional.empty();
        }
        JsonNode categoryNode = root.get("category");
        Long categoryId = categoryNode != null && categoryNode.isString()
                ? categories.get(categoryNode.asString().strip())
                : null;
        return Optional.of(new Naming(topic, categoryId));
    }

    private String readSystemPrompt() {
        try (var input = systemPromptResource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("미응답 묶음 이름 프롬프트를 읽지 못했습니다.", exception);
        }
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("topic", Map.of("type", "string"));
        properties.put("category", Map.of("type", List.of("string", "null")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(properties));
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }

    record Naming(String topic, Long categoryId) {
    }
}
