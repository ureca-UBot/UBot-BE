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
import tools.jackson.databind.DeserializationFeature;
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
    // 알려진 메타데이터의 독립된 끝줄만 분리합니다. 숫자 근거 ID는 경고 검사에 그대로 전달합니다.
    private static final Pattern TEXT_METADATA_LINE = Pattern.compile(
            "(?i)^\\h*(?:\\*{1,2}|`)?[\"']?(?<key>status|evidence_ids)[\"']?(?:\\*{1,2}|`)?"
                    + "\\h*[:：]\\h*(?<value>.*?)\\h*$");
    private static final Pattern METADATA_EXAMPLE_CONTEXT = Pattern.compile(
            "(?:예시|예제|샘플|형식|필드|코드|인용)[^\\n]*(?:[:：]|입니다[.。]?)$");
    private static final Pattern TRAILING_RESPONSE_OBJECT = Pattern.compile(
            "\\R\\h*(\\{\\s*\\\"(?:answers|answer)\\\"\\s*:)");
    // 사실·조건·개인 정보 요청은 포함하지 않는 마지막 문의 권유만 응답 서식으로 정리합니다.
    private static final Pattern CLOSING_OFFER = Pattern.compile(
            "(?s)(?:^|(?<=[.!?。！？])\\s+|\\R+)"
                    + "(?:(?:추가(?:적인)?\\s*(?:정보|도움)|더\\s*(?:많은\\s*)?(?:정보|도움))(?:가|이)?\\s*필요하시면"
                    + "|(?:(?:추가(?:로)?|더)\\s*)?(?:궁금(?:하신|한)\\s*(?:점|사항)|문의\\s*사항)(?:이|가)?\\s*있으시면"
                    + "|추가(?:로)?\\s*확인이\\s*필요한\\s*사항(?:이|가)?\\s*있(?:으면|으시면)"
                    + "|(?:추가(?:로)?\\s*)?(?:(?:요금제|서비스|상품|변경|추가|이용|청구|사용량|관련|대한|등|이나|나|와|과|에|의)\\s*){0,16}"
                    + "문의(?:가|사항이)?\\s*있(?:으면|으시면))"
                    + "\\s*(?:언제든(?:지)?\\s*)?(?:말씀(?:해)?|문의(?:해)?)\\s*주세요[.!?。！？]*[\\s\\p{So}\\p{Sk}\\uFE0F]*$");

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
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
     * JSON이 아닌 일반 문장은 알려진 후행 필드와 마지막 문의 권유만 분리해 answer로 씁니다. JSON처럼 보이는데 읽을 수 없거나 answer가 비어 있으면
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
            return parseTextAnswer(text);
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
        return new FaqAnswer(normalizeAnswer(answer.asString()), readEvidenceIds(root.get("evidence_ids")),
                readStatus(root.get("status")));
    }

    /** 칸의 answer가 없으면 빈 문자열, status가 SLOT_STATUSES에 없으면 null로 둡니다. 칸을 어떻게 쓸지는 AiService가 정합니다. */
    private static List<SlotAnswer> readSlots(JsonNode answers) {
        List<SlotAnswer> slots = new ArrayList<>();
        for (JsonNode slot : answers) {
            JsonNode text = slot.get("answer");
            JsonNode status = slot.get("status");
            String value = status != null && status.isString() ? status.asString().strip() : null;
            slots.add(new SlotAnswer(text != null && text.isString() ? normalizeAnswer(text.asString()) : "",
                    value != null && SLOT_STATUSES.contains(value) ? value : null));
        }
        return List.copyOf(slots);
    }

    private static FaqAnswer parseTextAnswer(String text) {
        Matcher object = TRAILING_RESPONSE_OBJECT.matcher(text);
        if (object.find()) {
            try {
                FaqAnswer structured = parse(text.substring(object.start(1)));
                String body = structured.slots() == null ? structured.answer()
                        : String.join("\n\n", structured.slots().stream().map(SlotAnswer::answer).toList());
                String prefix = normalizeAnswer(text.substring(0, object.start(1)));
                // JSON 앞의 본문도 내용이 같을 때만 중복 서식으로 봅니다. 다른 문장은 버리지 않습니다.
                if (sameBody(prefix, body)) return structured;
            } catch (LlmException ignored) {
                // 손상되거나 다른 내용의 응답은 원문을 남겨 기존 검사로 전체 거절합니다.
            }
        }
        TextFooter footer = readTextFooter(text);
        if (footer != null) {
            return new FaqAnswer(normalizeAnswer(footer.answer()), footer.evidenceIds(), footer.status());
        }
        return new FaqAnswer(normalizeAnswer(text), List.of(), null);
    }

    private static boolean sameBody(String first, String second) {
        return first != null && second != null
                && first.replaceAll("\\s+", " ").strip().equals(second.replaceAll("\\s+", " ").strip());
    }

    private static TextFooter readTextFooter(String text) {
        String[] lines = text.split("\\R", -1);
        int lastBodyLine = lines.length - 1;
        String status = null;
        List<String> evidenceIds = null;
        boolean found = false;
        while (lastBodyLine >= 0) {
            String line = lines[lastBodyLine].strip();
            if (line.isEmpty() || line.matches("(?:-{3,}|_{3,}|\\*{3,})")) {
                lastBodyLine--;
                continue;
            }
            Matcher field = TEXT_METADATA_LINE.matcher(line);
            if (!field.matches()) break;
            String value = field.group("value").replaceFirst("(?:\\*{1,2}|`)+\\s*$", "").strip();
            if (field.group("key").equalsIgnoreCase("status")) {
                if (status != null) return null;
                value = value.replaceAll("^[\"']|[\"']$", "");
                if (!STATUSES.contains(value)) return null;
                status = value;
            } else {
                if (evidenceIds != null) return null;
                evidenceIds = textEvidenceIds(value);
                if (evidenceIds == null) return null;
            }
            found = true;
            lastBodyLine--;
        }
        if (!found || lastBodyLine < 0) return null;
        String body = String.join("\n", java.util.Arrays.copyOf(lines, lastBodyLine + 1)).strip();
        String lastLine = body.substring(body.lastIndexOf('\n') + 1);
        // 문서·코드 예제의 필드를 실제 응답 메타데이터로 바꾸지 않습니다.
        if ((body.split("```", -1).length - 1) % 2 != 0
                || METADATA_EXAMPLE_CONTEXT.matcher(lastLine).find()) return null;
        return new TextFooter(body, evidenceIds == null ? List.of() : evidenceIds, status);
    }

    private static List<String> textEvidenceIds(String value) {
        if (value.matches("[0-9]+(?:\\s*,\\s*[0-9]+)*")) {
            return List.of(value.split("\\s*,\\s*"));
        }
        try {
            JsonNode node = JSON_MAPPER.readTree(value);
            if (node == null) return null;
            List<JsonNode> values = new ArrayList<>();
            if (node.isArray()) node.forEach(values::add);
            else values.add(node);
            List<String> ids = new ArrayList<>();
            for (JsonNode id : values) {
                if (!id.isValueNode() || id.isNull() || !id.asString().matches("[0-9]+")) return null;
                ids.add(id.asString());
            }
            return List.copyOf(ids);
        } catch (JacksonException ignored) {
            // 해석할 수 없는 필드는 원문에 남겨 기존 출력 검사를 거칩니다.
            return null;
        }
    }

    private record TextFooter(String answer, List<String> evidenceIds, String status) {
    }

    private static String normalizeAnswer(String answer) {
        String original = answer.strip();
        String formatted = CLOSING_OFFER.matcher(original).replaceFirst("").stripTrailing();
        // 권유만 있는 응답은 정상 답변으로 만들지 않고 기존 가드의 전체 거절 대상으로 둡니다.
        return StringUtils.hasText(formatted) ? formatted : original;
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
     * 파싱한 모델 출력입니다. status는 모델이 목록에 없는 값을 쓰거나 상태 필드가 없는 답변이면 null입니다.
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
