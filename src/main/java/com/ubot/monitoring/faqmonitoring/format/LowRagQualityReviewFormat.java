package com.ubot.monitoring.faqmonitoring.format;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.LowRagQualityQuestionAssessment;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.LowRagQualityRepresentativeAssessment;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.LowRagQualityVariantAssessment;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class LowRagQualityReviewFormat {
	private static final Pattern CODE_BLOCK = Pattern.compile("(?si)^```(?:json)?\\s*(.*?)\\s*```$");
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
	private static final Map<String, Object> REPRESENTATIVE_SCHEMA = createRepresentativeSchema();
	private static final Map<String, Object> VARIANT_SCHEMA = createVariantSchema();

	private LowRagQualityReviewFormat() {
	}

	public static Map<String, Object> representativeSchema() {
		return REPRESENTATIVE_SCHEMA;
	}

	public static Map<String, Object> variantSchema() {
		return VARIANT_SCHEMA;
	}

	public static LowRagQualityRepresentativeAssessment parseRepresentative(String output) {
		LowRagQualityRepresentativeAssessment assessment = read(
				output, LowRagQualityRepresentativeAssessment.class);
		if (!StringUtils.hasText(assessment.reason())
				|| isNotApplicable(assessment.reason())
				|| !StringUtils.hasText(assessment.suggestedQuestion())
				|| assessment.needsUpdate() == isNotApplicable(assessment.suggestedQuestion())) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}
		return assessment;
	}

	private static boolean isNotApplicable(String value) {
		return value.strip().replaceAll("\\s+", "").equals("해당없음");
	}

	public static LowRagQualityVariantAssessment parseVariant(String output, int expectedQuestionCount) {
		LowRagQualityVariantAssessment assessment = read(output, LowRagQualityVariantAssessment.class);
		if (assessment.questionAssessments() == null
				|| assessment.questionAssessments().size() != expectedQuestionCount) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}

		Set<Integer> questionNumbers = new HashSet<>();
		for (LowRagQualityQuestionAssessment question : assessment.questionAssessments()) {
			if (question == null || question.questionNumber() < 1
					|| question.questionNumber() > expectedQuestionCount
					|| !questionNumbers.add(question.questionNumber())) {
				throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
			}
		}
		return assessment;
	}

	private static <T> T read(String output, Class<T> type) {
		if (!StringUtils.hasText(output)) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}
		String text = output.strip();
		Matcher codeBlock = CODE_BLOCK.matcher(text);
		if (codeBlock.matches()) {
			text = codeBlock.group(1).strip();
		}
		try {
			T result = JSON_MAPPER.readValue(text, type);
			if (result == null) {
				throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
			}
			return result;
		} catch (JacksonException exception) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID, exception);
		}
	}

	private static Map<String, Object> createRepresentativeSchema() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("needsUpdate", Map.of("type", "boolean"));
		properties.put("reason", Map.of("type", "string"));
		properties.put("suggestedQuestion", Map.of("type", "string"));
		return objectSchema(properties);
	}

	private static Map<String, Object> createVariantSchema() {
		Map<String, Object> questionProperties = new LinkedHashMap<>();
		questionProperties.put("questionNumber", Map.of("type", "integer"));
		questionProperties.put("answerableByCurrentFaq", Map.of("type", "boolean"));
		Map<String, Object> questionSchema = objectSchema(questionProperties);

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("questionAssessments", Map.of(
				"type", "array",
				"items", questionSchema
		));
		return objectSchema(properties);
	}

	private static Map<String, Object> objectSchema(Map<String, Object> properties) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", Collections.unmodifiableMap(new LinkedHashMap<>(properties)));
		schema.put("required", List.copyOf(properties.keySet()));
		schema.put("additionalProperties", false);
		return Collections.unmodifiableMap(schema);
	}
}
