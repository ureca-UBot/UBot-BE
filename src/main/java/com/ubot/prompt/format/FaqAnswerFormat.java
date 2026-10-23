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
 * faq-system.txt(단일 status)와 faq-system-slot.txt(하위 질문별 칸)가 모델에게 요구하는 출력 JSON의 스키마와 파서입니다.
 * 프롬프트의 키 이름·status 목록을 바꾸면 이 클래스도 함께 바꿔야 합니다.
 * 키 순서는 프롬프트 서술(evidence_ids 먼저)과 달리 answer 먼저입니다(schema() 참고).
 */
public final class FaqAnswerFormat {

    /** 프롬프트의 status 목록과 같습니다. */
    public static final List<String> STATUSES = List.of("ANSWER", "PARTIAL", "ABSTAIN", "CONFLICT", "OUT_OF_SCOPE");
    /**
     * 칸 형식(faq-system-slot.txt)에서 하위 질문마다 고르는 status입니다. PARTIAL은 없고,
     * 전체 status는 코드가 하위 status를 조합해 정합니다(STATUSES 중 하나).
     */
    public static final List<String> SLOT_STATUSES = List.of("ANSWER", "ABSTAIN", "CONFLICT", "OUT_OF_SCOPE");

    // 모델이 JSON을 마크다운 코드 블록으로 감싸 보내는 경우(스키마를 강제하지 못하는 도구 호출 요청 등)를 벗겨 냅니다.
    private static final Pattern CODE_BLOCK = Pattern.compile("(?si)^```(?:json)?\\s*(.*?)\\s*```$");
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final Map<String, Object> SCHEMA = createSchema();

    private FaqAnswerFormat() {
    }

    /**
     * 출력 JSON Schema입니다. properties 순서가 출력 키 순서입니다.
     * answer를 먼저 둔 이유: model_test_v5에서 Qwen3-Instruct가 스키마 순서와 관계없이 answer를 먼저 썼고,
     * 그 순서로 측정한 결과를 그대로 쓰기 위해서입니다. vLLM은 스키마의 키 순서를 그대로 강제합니다.
     */
    public static Map<String, Object> schema() {
        return SCHEMA;
    }

    /**
     * 칸 형식의 출력 JSON Schema입니다. answers는 하위 질문 수(slots)만큼의 칸이고, 칸마다 answer를 먼저 쓰고 status를 정합니다.
     * status를 먼저 쓰게 하면 답이 status에 끌려가 성능이 떨어졌습니다(LLM_Test pipeline_test_v4).
     */
    public static Map<String, Object> slotSchema(int slots) {
        Map<String, Object> slotProperties = new LinkedHashMap<>();
        slotProperties.put("answer", Map.of("type", "string"));
        slotProperties.put("status", Map.of("type", "string", "enum", SLOT_STATUSES));
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("type", "object");
        slot.put("properties", Collections.unmodifiableMap(slotProperties));
        slot.put("required", List.copyOf(slotProperties.keySet()));
        slot.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("answers", Map.of("type", "array", "items", Collections.unmodifiableMap(slot),
                "minItems", slots, "maxItems", slots));
        properties.put("evidence_ids", Map.of("type", "array", "items", Map.of("type", "string")));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(properties));
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }

    /**
     * 모델 출력에서 화면에 보여 줄 answer와 근거·상태를 꺼냅니다.
     * JSON이 아닌 일반 문장은 그대로 answer로 씁니다. JSON처럼 보이는데 읽을 수 없거나 answer가 비어 있으면
     * 원문을 사용자에게 보여 주지 않도록 LLM_RESPONSE_INVALID로 실패시킵니다.
     */
    public static FaqAnswer parse(String output) {
        if (!StringUtils.hasText(output)) {
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }
        String text = output.strip();
        Matcher codeBlock = CODE_BLOCK.matcher(text);
        if (codeBlock.matches()) {
            text = codeBlock.group(1).strip();
        }
        if (!text.startsWith("{")) {
            return new FaqAnswer(text, List.of(), null);
        }

        JsonNode root;
        try {
            root = JSON_MAPPER.readTree(text);
        } catch (JacksonException exception) {
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID, exception);
        }
        JsonNode answers = root.get("answers");
        if (answers != null && answers.isArray()) {
            return new FaqAnswer(null, readEvidenceIds(root.get("evidence_ids")), null, readSlots(answers));
        }
        JsonNode answer = root.get("answer");
        if (answer == null || !answer.isString() || !StringUtils.hasText(answer.asString())) {
            throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
        }
        return new FaqAnswer(answer.asString().strip(), readEvidenceIds(root.get("evidence_ids")),
                readStatus(root.get("status")));
    }

    /** 칸의 answer가 없으면 빈 문자열, status가 SLOT_STATUSES에 없으면 null로 둡니다. 칸을 어떻게 쓸지는 AiService가 정합니다. */
    private static List<SlotAnswer> readSlots(JsonNode answers) {
        List<SlotAnswer> slots = new ArrayList<>();
        for (JsonNode slot : answers) {
            JsonNode text = slot.get("answer");
            JsonNode status = slot.get("status");
            String value = status != null && status.isString() ? status.asString().strip() : null;
            slots.add(new SlotAnswer(text != null && text.isString() ? text.asString().strip() : "",
                    SLOT_STATUSES.contains(value) ? value : null));
        }
        return List.copyOf(slots);
    }

    private static List<String> readEvidenceIds(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode id : node) {
            if (id.isValueNode() && !id.isNull() && StringUtils.hasText(id.asString())) {
                ids.add(id.asString().strip());
            }
        }
        return List.copyOf(ids);
    }

    private static String readStatus(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        String status = node.asString().strip();
        return STATUSES.contains(status) ? status : null;
    }

    private static Map<String, Object> createSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("answer", Map.of("type", "string"));
        properties.put("evidence_ids", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("status", Map.of("type", "string", "enum", STATUSES));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(properties));
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }

    /**
     * 파싱한 모델 출력입니다. status는 모델이 목록에 없는 값을 쓰거나 JSON이 아닌 답변이면 null입니다.
     * evidenceIds는 FAQ ID 문자열이며 비어 있을 수 있습니다.
     * slots는 칸 형식 출력의 칸 목록이며, 칸 형식이 아니면 null입니다. 칸 형식이면 answer·status는 null입니다.
     */
    public record FaqAnswer(String answer, List<String> evidenceIds, String status, List<SlotAnswer> slots) {
        public FaqAnswer(String answer, List<String> evidenceIds, String status) {
            this(answer, evidenceIds, status, null);
        }
    }

    /** 칸 형식의 칸 하나입니다. status는 SLOT_STATUSES에 없는 값이면 null입니다. */
    public record SlotAnswer(String answer, String status) {
    }
}
