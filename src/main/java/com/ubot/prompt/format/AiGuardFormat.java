package com.ubot.prompt.format;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 가드 판정만 읽습니다. 기존 답변·질문 정리 JSON 파서는 변경하지 않습니다. */
public final class AiGuardFormat {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> INPUT_REASONS = Set.of("SAFE", "INJECTION", "FALSE_CLAIM", "PRIVACY",
            "ABUSE", "HARM", "CONTENT_ACCESS", "UNCERTAIN");
    private static final Set<String> OUTPUT_REASONS = Set.of("SAFE", "HARMFUL_REQUEST_FOLLOWED", "ABUSIVE_LANGUAGE",
            "INTERNAL_INFORMATION", "FALSE_EXECUTION", "POLICY_CONTRADICTION", "UNSUPPORTED_CLAIM", "WRONG_SERVICE",
            "CONDITION_DISTORTION", "INSUFFICIENT_EVIDENCE");

    private AiGuardFormat() {
    }

    public enum Decision { ALLOW, BLOCK, REVIEW }

    public record InputVerdict(Decision decision, String reason, List<String> subQuestions) {
    }

    public record OutputVerdict(Decision decision, List<String> reasons) {
    }

    public static InputVerdict parseInput(String response) {
        JsonNode root = object(response, Set.of("decision", "reason", "sub_questions"));
        Decision decision = decision(root);
        String reason = text(root.get("reason"));
        if (!INPUT_REASONS.contains(reason)
                || decision == Decision.ALLOW && !"SAFE".equals(reason)
                || decision == Decision.BLOCK && Set.of("SAFE", "UNCERTAIN").contains(reason)
                || decision == Decision.REVIEW && !"UNCERTAIN".equals(reason)) throw invalid();
        List<String> questions = strings(root.get("sub_questions"));
        if (decision == Decision.ALLOW ? questions.isEmpty() : !questions.isEmpty()) throw invalid();
        return new InputVerdict(decision, reason, questions);
    }

    public static OutputVerdict parseOutput(String response) {
        JsonNode root = object(response, Set.of("decision", "reason_codes", "explanation"));
        Decision decision = decision(root);
        List<String> reasons = strings(root.get("reason_codes"));
        if (reasons.isEmpty() || reasons.size() > 4 || !OUTPUT_REASONS.containsAll(reasons)
                || text(root.get("explanation")).codePointCount(0, text(root.get("explanation")).length()) > 240
                || decision == Decision.ALLOW && !reasons.equals(List.of("SAFE"))
                || decision != Decision.ALLOW && reasons.contains("SAFE")) throw invalid();
        return new OutputVerdict(decision, reasons);
    }

    private static JsonNode object(String response, Set<String> fields) {
        try {
            JsonNode root = JSON.readTree(response);
            if (root == null || !root.isObject() || root.size() != fields.size()
                    || fields.stream().anyMatch(field -> !root.has(field))) throw invalid();
            return root;
        } catch (LlmException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 응답 원문을 예외 메시지·로그로 전달하지 않습니다.
            throw invalid();
        }
    }

    private static Decision decision(JsonNode root) {
        try {
            return Decision.valueOf(text(root.get("decision")));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isString()) throw invalid();
        return node.asString();
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) throw invalid();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = text(item);
            if (value.isBlank()) throw invalid();
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static LlmException invalid() {
        return new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
    }
}
