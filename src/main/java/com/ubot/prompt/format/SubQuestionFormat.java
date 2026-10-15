package com.ubot.prompt.format;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * sub-question-system.txt가 판정 모델에게 요구하는 출력 JSON의 스키마와 파서입니다.
 * 프롬프트의 키 이름·순서를 바꾸면 이 클래스도 함께 바꿔야 합니다.
 */
public final class SubQuestionFormat {

    private static final Pattern CODE_BLOCK = Pattern.compile("(?si)^```(?:json)?\\s*(.*?)\\s*```$");
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final Map<String, Object> SCHEMA = createSchema();

    private SubQuestionFormat() {
    }

    /**
     * 출력 JSON Schema입니다. sub_questions 하나만 씁니다.
     * sub_questions에 개수 제한(maxItems)을 두지 않습니다. 모델이 사용자가 물은 질문을 모두 적어야
     * 질문이 너무 많은지 서버가 판단할 수 있습니다.
     */
    public static Map<String, Object> schema() {
        return SCHEMA;
    }

    /**
     * 모델 출력에서 하위 질문을 꺼냅니다. 앞뒤 공백을 지우고 빈 질문은 버립니다.
     * JSON이 아니거나 읽을 수 없거나 하위 질문이 하나도 없으면 LLM_RESPONSE_INVALID로 실패시킵니다.
     */
    public static List<String> parse(String output) {
        if (!StringUtils.hasText(output)) {
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }
        String text = output.strip();
        Matcher codeBlock = CODE_BLOCK.matcher(text);
        if (codeBlock.matches()) {
            text = codeBlock.group(1).strip();
        }

        JsonNode root;
        try {
            root = JSON_MAPPER.readTree(text);
        } catch (JacksonException exception) {
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID, exception);
        }
        JsonNode questions = root == null ? null : root.get("sub_questions");
        if (questions == null || !questions.isArray()) {
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }
        List<String> subQuestions = new ArrayList<>();
        for (JsonNode question : questions) {
            if (question.isString() && StringUtils.hasText(question.asString())) {
                subQuestions.add(question.asString().strip());
            }
        }
        if (subQuestions.isEmpty()) {
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }
        return List.copyOf(subQuestions);
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sub_questions", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(properties));
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }
}
