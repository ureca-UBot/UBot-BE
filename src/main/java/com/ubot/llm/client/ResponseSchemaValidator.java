package com.ubot.llm.client;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * 도구 경로의 최종 답변이 요청한 JSON Schema를 지켰는지 검사합니다. 위반이 있으면 LlmClient가 스키마를 걸어 1회 재호출합니다.
 *
 * <p>검사 자체는 JSON Schema 표준 검사기(networknt, Draft 2020-12)가 합니다. 스키마에 어떤 type·키워드를 추가해도
 * 이 클래스를 고치지 않고 그대로 검사됩니다. 답변 내용의 사실 여부는 검사하지 않습니다.
 *
 * <p>기준은 "답변 파서({@code FaqAnswerFormat})가 정상으로 읽는 것은 재호출하지 않는다"입니다. 파서가 너그럽게 받아 주는 부분은
 * 표준 검사 전에 {@link #normalize}로 맞춰 둡니다. 여기에 없는 차이는 표준 검사 결과를 그대로 따릅니다.
 *
 * <p>위반 결과는 {@code "<위치>:<사유>"} 형식의 짧은 문자열입니다. 위치는 JSONPath 표기, 사유는 어긴 키워드 이름입니다
 * (예: {@code $.answers[0].status:enum}, {@code $.answers:minItems}). 로그에 남기므로 모델 본문이나 필드 값은 넣지 않습니다.
 */
final class ResponseSchemaValidator {

    /** 모델이 JSON을 코드 블록(```json ... ```)으로 감싸도 안쪽 JSON을 검사합니다. 답변 파싱도 이를 허용합니다. */
    private static final Pattern CODE_BLOCK = Pattern.compile("(?si)^```(?:json)?\\s*(.*?)\\s*```$");

    /** JSON 뒤에 다른 내용이 더 붙어 있으면(예: 객체 두 개) 읽기 실패로 처리합니다. */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** 같은 스키마(예: 칸 수가 같은 slotSchema)를 매번 다시 해석하지 않습니다. 스키마 Map은 내용으로 비교됩니다. */
    private static final Map<Map<String, Object>, Schema> COMPILED = new ConcurrentHashMap<>();

    /** 파서가 숫자도 문자열로 바꿔 읽는 ID 목록입니다({@code FaqAnswerFormat.readEvidenceIds}). */
    private static final String EVIDENCE_IDS = "evidence_ids";

    private static final String ROOT = "$";
    private static final String EMPTY = "empty";               // 답변이 비어 있음
    private static final String INVALID_JSON = "invalid-json"; // JSON으로 읽을 수 없음

    private ResponseSchemaValidator() {}

    /**
     * 첫 번째 위반 하나만 돌려줍니다. 정상이면 null입니다.
     *
     * @param text   모델의 최종 답변 원문(추론 텍스트를 뗀 뒤)
     * @param schema 요청에 실은 JSON Schema
     */
    static String violation(String text, Map<String, Object> schema) {
        if (!StringUtils.hasText(text)) return violationAt(ROOT, EMPTY);

        JsonNode answer;
        try {
            answer = MAPPER.readTree(unwrapCodeBlock(text.strip()));
        } catch (JacksonException exception) {
            return violationAt(ROOT, INVALID_JSON);
        }

        List<Error> errors = compile(schema).validate(normalize(answer));
        if (errors.isEmpty()) return null;
        Error first = errors.getFirst();
        return violationAt(toJsonPath(first), first.getKeyword());
    }

    private static String unwrapCodeBlock(String text) {
        var codeBlock = CODE_BLOCK.matcher(text);
        return codeBlock.matches() ? codeBlock.group(1).strip() : text;
    }

    private static Schema compile(Map<String, Object> schema) {
        return COMPILED.computeIfAbsent(schema, key -> REGISTRY.getSchema(MAPPER.<JsonNode>valueToTree(key)));
    }

    /**
     * 파서가 정상으로 읽는 차이를 표준 검사 전에 맞춥니다. 원본은 바꾸지 않고 복사본을 돌려줍니다.
     * <ul>
     *   <li>문자열 값의 앞뒤 공백: 파서가 strip()해서 읽으므로 제거합니다(예: status {@code " ANSWER"}).</li>
     *   <li>최상위 evidence_ids의 숫자: 파서가 문자열로 바꿔 읽으므로 문자열로 바꿉니다(예: {@code [12]} → {@code ["12"]}).</li>
     * </ul>
     * 그 밖의 값은 바꾸지 않습니다. 예를 들어 {@code "answer": 12}는 숫자 그대로 남아 type 위반이 됩니다.
     */
    private static JsonNode normalize(JsonNode answer) {
        JsonNode copy = stripStrings(answer.deepCopy());
        if (copy instanceof ObjectNode root && root.get(EVIDENCE_IDS) instanceof ArrayNode ids) {
            for (int index = 0; index < ids.size(); index++) {
                if (ids.get(index).isNumber()) ids.set(index, StringNode.valueOf(ids.get(index).asString()));
            }
        }
        return copy;
    }

    private static JsonNode stripStrings(JsonNode node) {
        if (node.isString()) return StringNode.valueOf(node.asString().strip());
        if (node instanceof ObjectNode object) {
            for (String key : List.copyOf(object.propertyNames())) object.set(key, stripStrings(object.get(key)));
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) array.set(index, stripStrings(array.get(index)));
        }
        return node;
    }

    /**
     * 위반 위치를 {@code $.answers[0].status} 형식으로 만듭니다. 필수 키가 없을 때(required)는 그 키까지 붙입니다
     * (예: {@code $.answers[0].status:required}).
     */
    private static String toJsonPath(Error error) {
        StringBuilder path = new StringBuilder(ROOT);
        NodePath location = error.getInstanceLocation();
        for (int index = 0; index < location.getNameCount(); index++) {
            Object element = location.getElement(index);
            if (element instanceof Number number) path.append('[').append(number).append(']');
            else path.append('.').append(element);
        }
        if ("required".equals(error.getKeyword()) && error.getProperty() != null) {
            path.append('.').append(error.getProperty());
        }
        return path.toString();
    }

    private static String violationAt(String path, String reason) {
        return path + ":" + reason;
    }
}
