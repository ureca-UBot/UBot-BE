package com.ubot.monitoring.faqmonitoring.format;

import com.ubot.llm.exception.LlmErrorCode;
import com.ubot.llm.exception.LlmException;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateCandidateAssessment;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateReviewAction;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateReviewResult;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.DuplicateSuspectedAssessment;
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

public final class DuplicateSuspectedReviewFormat {
	private static final Pattern CODE_BLOCK = Pattern.compile("(?si)^```(?:json)?\\s*(.*?)\\s*```$");
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
	private static final Map<String, Object> SCHEMA = createSchema();

	private DuplicateSuspectedReviewFormat() {
	}

	public static Map<String, Object> schema() {
		return SCHEMA;
	}

	public static DuplicateSuspectedAssessment parse(String output, List<Long> expectedCandidateIds) {
		DuplicateSuspectedAssessment assessment = read(output);
		if (assessment.candidateAssessments() == null
				|| assessment.candidateAssessments().size() != expectedCandidateIds.size()) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}

		Set<Long> expectedIds = new HashSet<>(expectedCandidateIds);
		Set<Long> actualIds = new HashSet<>();
		for (DuplicateCandidateAssessment candidate : assessment.candidateAssessments()) {
			if (candidate == null || candidate.candidateFaqId() == null
					|| !expectedIds.contains(candidate.candidateFaqId())
					|| !actualIds.add(candidate.candidateFaqId())
					|| candidate.result() == null
					|| candidate.action() == null
					|| !isAllowedCombination(candidate.result(), candidate.action())
					|| !hasText(candidate.reason(),
						candidate.targetFaqRecommendation(), candidate.candidateFaqRecommendation())) {
				throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
			}
		}
		if (!actualIds.equals(expectedIds)) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}
		return assessment;
	}

	private static boolean isAllowedCombination(
			DuplicateReviewResult result,
			DuplicateReviewAction action
	) {
		return switch (result) {
			case DUPLICATE -> action == DuplicateReviewAction.MERGE_CANDIDATE_INTO_TARGET
					|| action == DuplicateReviewAction.MERGE_TARGET_INTO_CANDIDATE;
			case NEEDS_DISTINCTION -> action == DuplicateReviewAction.CLARIFY_SCOPE;
			case NOT_DUPLICATE -> action == DuplicateReviewAction.KEEP_SEPARATE;
			case INSUFFICIENT_EVIDENCE -> action == DuplicateReviewAction.COLLECT_MORE_EVIDENCE;
		};
	}

	private static boolean hasText(String... values) {
		for (String value : values) {
			if (!StringUtils.hasText(value)) {
				return false;
			}
		}
		return true;
	}

	private static DuplicateSuspectedAssessment read(String output) {
		if (!StringUtils.hasText(output)) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
		}
		String text = output.strip();
		Matcher codeBlock = CODE_BLOCK.matcher(text);
		if (codeBlock.matches()) {
			text = codeBlock.group(1).strip();
		}
		try {
			DuplicateSuspectedAssessment result = JSON_MAPPER.readValue(text, DuplicateSuspectedAssessment.class);
			if (result == null) {
				throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID);
			}
			return result;
		} catch (JacksonException exception) {
			throw new LlmException(LlmErrorCode.LLM_RESPONSE_INVALID, exception);
		}
	}

	private static Map<String, Object> createSchema() {
		Map<String, Object> candidateProperties = new LinkedHashMap<>();
		candidateProperties.put("candidateFaqId", Map.of("type", "integer"));
		candidateProperties.put("result", Map.of(
				"type", "string",
				"enum", enumNames(DuplicateReviewResult.values())
		));
		candidateProperties.put("action", Map.of(
				"type", "string",
				"enum", enumNames(DuplicateReviewAction.values())
		));
		candidateProperties.put("reason", Map.of("type", "string"));
		candidateProperties.put("targetFaqRecommendation", Map.of("type", "string"));
		candidateProperties.put("candidateFaqRecommendation", Map.of("type", "string"));
		Map<String, Object> candidateSchema = objectSchema(candidateProperties);

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("candidateAssessments", Map.of(
				"type", "array",
				"items", candidateSchema
		));
		return objectSchema(properties);
	}

	private static List<String> enumNames(Enum<?>[] values) {
		return java.util.Arrays.stream(values).map(Enum::name).toList();
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
