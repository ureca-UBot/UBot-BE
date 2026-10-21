package com.ubot.monitoring.faqmonitoring.format;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.monitoring.faqmonitoring.dto.response.AiReviewResponse;
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

public final class AiReviewFormat {
	private static final Pattern CODE_BLOCK = Pattern.compile("(?si)^```(?:json)?\\s*(.*?)\\s*```$");
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
	private static final Map<String, Object> SCHEMA = createSchema();

	private AiReviewFormat() {
	}

	public static Map<String, Object> schema() {
		return SCHEMA;
	}

	public static AiReviewResponse parse(String output) {
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
		JsonNode recommendation = root == null ? null : root.get("recommendation");
		JsonNode details = root == null ? null : root.get("details");
		if (recommendation == null || !recommendation.isString()
				|| !StringUtils.hasText(recommendation.asString())
				|| details == null || !details.isString() || !StringUtils.hasText(details.asString())) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}
		return new AiReviewResponse(recommendation.asString().strip(), details.asString().strip());
	}

	private static Map<String, Object> createSchema() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("recommendation", Map.of("type", "string"));
		properties.put("details", Map.of("type", "string"));
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", Collections.unmodifiableMap(properties));
		schema.put("required", List.copyOf(properties.keySet()));
		schema.put("additionalProperties", false);
		return Collections.unmodifiableMap(schema);
	}
}
