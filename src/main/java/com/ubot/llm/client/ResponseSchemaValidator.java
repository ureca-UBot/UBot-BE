package com.ubot.llm.client;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 도구 경로의 최종 답변이 요청한 JSON Schema 모양을 지켰는지 검사합니다. 위반이 있으면 LlmClient가 스키마를 걸어 1회 재호출합니다.
 *
 * <p>답변 스키마({@code FaqAnswerFormat.slotSchema}, {@code FaqAnswerFormat.schema})가 쓰는 제약만 지원합니다.
 * <ul>
 *   <li>object: {@code required}(필수 키), {@code additionalProperties: false}(모르는 키 금지), {@code properties}(키별 하위 스키마)</li>
 *   <li>array: {@code minItems}·{@code maxItems}(칸 수), {@code items}(원소 스키마)</li>
 *   <li>string: 문자열 타입, {@code enum}(허용 값 목록, 예: status)</li>
 * </ul>
 * 그 밖의 타입(number, boolean 등)이 스키마에 들어오면 정상으로 넘기지 않고 {@code unsupported-schema-type}을 돌려줍니다.
 * 스키마를 확장하면 이 검사기도 함께 확장해야 합니다. 답변 내용의 사실 여부는 검사하지 않습니다.
 *
 * <p>위반 결과는 {@code "<위치>:<사유>"} 형식의 짧은 문자열입니다. 위치는 JSONPath 표기입니다
 * (예: {@code $.answers[0].status:invalid-enum}). 로그에 남기므로 모델 본문이나 필드 값은 넣지 않습니다.
 */
final class ResponseSchemaValidator {

    /** 모델이 JSON을 코드 블록(```json ... ```)으로 감싸도 안쪽 JSON을 검사합니다. 답변 파싱도 이를 허용합니다. */
    private static final Pattern CODE_BLOCK = Pattern.compile("(?si)^```(?:json)?\\s*(.*?)\\s*```$");

    /** JSON 뒤에 다른 내용이 더 붙어 있으면(예: 객체 두 개) 읽기 실패로 처리합니다. */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private static final String ROOT = "$";

    // 위반 사유
    private static final String EMPTY = "empty";                       // 답변이 비어 있음
    private static final String INVALID_JSON = "invalid-json";         // JSON으로 읽을 수 없음
    private static final String NOT_OBJECT = "not-object";
    private static final String MISSING = "missing";                   // required 키가 없음
    private static final String EXTRA_PROPERTY = "extra-property";     // 스키마에 없는 키가 있음
    private static final String NOT_ARRAY = "not-array";
    private static final String TOO_FEW_ITEMS = "too-few-items";       // 칸 수가 minItems보다 적음
    private static final String TOO_MANY_ITEMS = "too-many-items";     // 칸 수가 maxItems보다 많음
    private static final String NOT_STRING = "not-string";             // null·숫자 등 문자열이 아닌 값
    private static final String INVALID_ENUM = "invalid-enum";         // enum 목록에 없는 값
    private static final String UNSUPPORTED_SCHEMA_TYPE = "unsupported-schema-type";

    private ResponseSchemaValidator() {}

    /**
     * 첫 번째 위반 하나만 돌려줍니다. 정상이면 null입니다.
     *
     * @param text   모델의 최종 답변 원문(추론 텍스트를 뗀 뒤)
     * @param schema 요청에 실은 JSON Schema
     */
    static String violation(String text, Map<String, Object> schema) {
        if (!StringUtils.hasText(text)) return violationAt(ROOT, EMPTY);

        JsonNode root;
        try {
            root = MAPPER.readTree(unwrapCodeBlock(text.strip()));
        } catch (JacksonException exception) {
            return violationAt(ROOT, INVALID_JSON);
        }
        return check(root, schema, ROOT);
    }

    private static String unwrapCodeBlock(String text) {
        var codeBlock = CODE_BLOCK.matcher(text);
        return codeBlock.matches() ? codeBlock.group(1).strip() : text;
    }

    /** 스키마의 type에 따라 해당 검사로 보냅니다. 하위 값은 각 검사에서 다시 이 메서드를 불러 재귀로 내려갑니다. */
    private static String check(JsonNode node, Map<String, Object> schema, String path) {
        Object type = schema.get("type");
        if ("object".equals(type)) return checkObject(node, schema, path);
        if ("array".equals(type)) return checkArray(node, schema, path);
        if ("string".equals(type)) return checkString(node, schema, path);
        return violationAt(path, UNSUPPORTED_SCHEMA_TYPE);
    }

    /** 검사 순서: 객체인지 → 필수 키가 모두 있는지 → 모르는 키가 없는지 → 있는 키마다 하위 스키마 검사. */
    private static String checkObject(JsonNode node, Map<String, Object> schema, String path) {
        if (node == null || !node.isObject()) return violationAt(path, NOT_OBJECT);

        Map<String, Map<String, Object>> properties = propertiesOf(schema);

        for (String key : requiredKeysOf(schema)) {
            if (node.get(key) == null) return violationAt(path + "." + key, MISSING);
        }

        boolean forbidsUnknownKeys = Boolean.FALSE.equals(schema.get("additionalProperties"));
        if (forbidsUnknownKeys) {
            for (String key : node.propertyNames()) {
                if (!properties.containsKey(key)) return violationAt(path, EXTRA_PROPERTY);
            }
        }

        // 필수가 아닌 키는 없어도 됩니다. 있는 키만 하위 스키마로 검사합니다.
        for (var property : properties.entrySet()) {
            JsonNode value = node.get(property.getKey());
            if (value == null) continue;
            String violation = check(value, property.getValue(), path + "." + property.getKey());
            if (violation != null) return violation;
        }
        return null;
    }

    /**
     * 검사 순서: 배열인지 → 원소 수가 minItems~maxItems 안인지(칸 수 검사) → 원소마다 items 스키마 검사.
     * 단, ID 목록({@link #isIdList})의 숫자 원소는 정상으로 봅니다.
     */
    private static String checkArray(JsonNode node, Map<String, Object> schema, String path) {
        if (node == null || !node.isArray()) return violationAt(path, NOT_ARRAY);

        if (schema.get("minItems") instanceof Number min && node.size() < min.intValue())
            return violationAt(path, TOO_FEW_ITEMS);
        if (schema.get("maxItems") instanceof Number max && node.size() > max.intValue())
            return violationAt(path, TOO_MANY_ITEMS);

        Map<String, Object> itemSchema = itemSchemaOf(schema);
        if (itemSchema == null) return null;
        boolean idList = isIdList(itemSchema);
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            if (idList && item.isNumber()) continue;
            String violation = check(item, itemSchema, path + "[" + index + "]");
            if (violation != null) return violation;
        }
        return null;
    }

    /** 검사 순서: 문자열인지 → enum이 있으면 그 목록 안의 값인지. 빈 문자열은 정상입니다(ABSTAIN 칸의 빈 answer 등). */
    private static String checkString(JsonNode node, Map<String, Object> schema, String path) {
        if (node == null || !node.isString()) return violationAt(path, NOT_STRING);

        if (schema.get("enum") instanceof Collection<?> allowed && !allowed.contains(node.asString()))
            return violationAt(path, INVALID_ENUM);
        return null;
    }

    /**
     * enum 없는 문자열의 배열은 ID 목록(evidence_ids)입니다. 스키마는 문자열이지만 모델이 {@code [12]}처럼 숫자로 써도
     * 답변 파싱({@code FaqAnswerFormat.readEvidenceIds})이 그대로 받아들이므로, 형식 오류로 보고 재호출하지 않습니다.
     */
    private static boolean isIdList(Map<String, Object> itemSchema) {
        return "string".equals(itemSchema.get("type")) && !itemSchema.containsKey("enum");
    }

    // 스키마는 Map<String, Object>로 들어오므로 하위 항목을 꺼낼 때 형변환이 필요합니다.

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> propertiesOf(Map<String, Object> schema) {
        return (Map<String, Map<String, Object>>) schema.getOrDefault("properties", Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> requiredKeysOf(Map<String, Object> schema) {
        return (Collection<String>) schema.getOrDefault("required", List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> itemSchemaOf(Map<String, Object> schema) {
        return schema.get("items") instanceof Map<?, ?> items ? (Map<String, Object>) items : null;
    }

    private static String violationAt(String path, String reason) {
        return path + ":" + reason;
    }
}
