package com.ubot.prompt.format;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.prompt.format.AiGuardFormat.Decision;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 출력 AI의 근거 목록만 읽습니다. 판정·사유·설명은 동일한 목록에서 도출합니다. */
public final class AiOutputGuardAssessment {
    public static final String CONTRACT_VERSION = "output-findings-v1";
    public static final int MAX_FINDINGS = 4;
    public static final int MAX_QUOTE_LENGTH = 240;

    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private AiOutputGuardAssessment() {
    }

    public enum Certainty { CONFIRMED, UNCERTAIN }

    public enum Type {
        HARMFUL_REQUEST_FOLLOWED("위험 요구 실행·권장"),
        ABUSIVE_LANGUAGE("직접적인 모욕·욕설"),
        INTERNAL_INFORMATION("비공개 내부 정보 노출"),
        FALSE_EXECUTION("근거 없는 실행 완료·수행 약속"),
        POLICY_CONTRADICTION("제공 정책·사실과 모순"),
        UNSUPPORTED_CLAIM("근거 없는 구체적 정보 단정"),
        WRONG_SERVICE("다른 업무·서비스의 근거 사용"),
        CONDITION_DISTORTION("근거의 조건·대상·수치·절차 왜곡"),
        INSUFFICIENT_EVIDENCE("판정에 필요한 자료·문맥 부족");

        private final String description;

        Type(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public record Finding(Certainty certainty, Type type, String answerQuote) {
    }

    /** findings는 모델이 선택한 유형과 인용을 그대로 보존합니다. 설명은 별도 의미 판단을 덧붙이지 않습니다. */
    public record Result(Decision decision, List<String> reasons, String explanation, List<Finding> findings) {
        public Result {
            reasons = List.copyOf(reasons);
            findings = List.copyOf(findings);
        }
    }

    /** 구형 판정 JSON이나 잘못된 근거를 복구·허용하지 않습니다. 입력 가드 파서는 사용하지 않습니다. */
    public static Result parse(String response, String candidateAnswer) {
        try {
            if (!StringUtils.hasText(candidateAnswer)) throw invalid();
            JsonNode root = JSON.readTree(response);
            requireFields(root, Set.of("findings"));
            JsonNode values = root.get("findings");
            if (!values.isArray() || values.size() > MAX_FINDINGS) throw invalid();
            List<Finding> findings = new ArrayList<>();
            for (JsonNode value : values) {
                requireFields(value, Set.of("certainty", "type", "answer_quote"));
                Certainty certainty = Certainty.valueOf(text(value.get("certainty")));
                Type type = Type.valueOf(text(value.get("type")));
                String quote = text(value.get("answer_quote"));
                if (!StringUtils.hasText(quote) || quote.codePointCount(0, quote.length()) > MAX_QUOTE_LENGTH
                        || !candidateAnswer.contains(quote)
                        || certainty == Certainty.CONFIRMED && type == Type.INSUFFICIENT_EVIDENCE) throw invalid();
                findings.add(new Finding(certainty, type, quote));
            }
            return result(findings);
        } catch (LlmException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 질문·답변·인용·서버 응답을 오류 메시지나 원인으로 전달하지 않습니다.
            throw invalid();
        }
    }

    private static Result result(List<Finding> findings) {
        List<Finding> confirmed = findings.stream().filter(f -> f.certainty() == Certainty.CONFIRMED).toList();
        Decision decision = !confirmed.isEmpty() ? Decision.BLOCK : findings.isEmpty() ? Decision.ALLOW : Decision.REVIEW;
        // 확정 위반이 있으면 보류 항목을 차단 사유로 섞지 않습니다. 전체 근거는 findings에 남습니다.
        List<Type> decisive = (decision == Decision.BLOCK ? confirmed : findings).stream()
                .map(Finding::type).distinct().toList();
        List<String> reasons = decisive.stream().map(Enum::name).toList();
        String description = decisive.stream().map(Type::description).collect(Collectors.joining(", "));
        String explanation = switch (decision) {
            case ALLOW -> "가드 판정: 허용. 보고된 위반·불확실 항목이 없습니다.";
            case BLOCK -> "가드 판정: 차단. 확정 위반 유형: " + description + ".";
            case REVIEW -> "가드 판정: 보류. 확인이 필요한 유형: " + description + ".";
        };
        return new Result(decision, reasons, explanation, findings);
    }

    private static void requireFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()
                || fields.stream().anyMatch(field -> !node.has(field))) throw invalid();
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isString()) throw invalid();
        return node.asString();
    }

    private static LlmException invalid() {
        return new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
    }
}
