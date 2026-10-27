package com.ubot.guardreview.service;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 분석에 필요한 당시 근거만 보존합니다. 프롬프트·금칙어 사전·화면용 카드 전체는 복제하지 않습니다. */
final class ReviewContextSnapshot {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private ReviewContextSnapshot() {
	}
	static Map<String, Object> output(AnswerMaterials materials, StoreMapResult stores) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("provided_faqs", materials.faqs());
		values.put("additional_evidence", materials.sections());
		values.put("history", materials.history());
		values.put("sub_questions", materials.subQuestions());
		values.put("store_results", stores);
		return compact(JSON.valueToTree(values));
	}
	static Map<String, Object> request(LlmRequestDto request) {
		try {
			return request.messages().stream().filter(m -> m.role() == LlmMessageRole.USER).findFirst()
					.map(m -> compact(JSON.readTree(m.content()))).orElse(Map.of("snapshot_unavailable", true));
		} catch (RuntimeException exception) {
			return Map.of("snapshot_unavailable", true);
		}
	}
	private static Map<String, Object> compact(JsonNode data) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (String key : List.of("history", "additional_evidence", "store_results", "evidence_ids",
				"original_question", "current_question")) {
			if (data.has(key) && !data.path(key).isNull()) result.put(key, data.path(key));
		}
		if (data.path("provided_faqs").isArray()) {
			var faqs = new java.util.ArrayList<Map<String, Object>>();
			for (JsonNode faq : data.path("provided_faqs")) faqs.add(fields(faq, "faqId", "question", "answer"));
			result.put("provided_faqs", faqs);
		}
		if (data.path("sub_questions").isArray()) {
			var questions = new java.util.ArrayList<Map<String, Object>>();
			for (JsonNode part : data.path("sub_questions")) {
				var question = fields(part, "searchQuery", "userText");
				var ids = new java.util.ArrayList<JsonNode>();
				for (JsonNode faq : part.path("faqs")) if (faq.has("faqId")) ids.add(faq.path("faqId"));
				question.put("faq_ids", ids);
				questions.add(question);
			}
			result.put("sub_questions", questions);
		}
		if (data.path("execution_results").isArray()) {
			var operations = new java.util.ArrayList<Map<String, Object>>();
			for (JsonNode execution : data.path("execution_results")) operations.add(fields(execution, "operation"));
			result.put("execution_results", operations);
			// 조회 수치·조건은 additional_evidence에 있습니다. 화면용 카드/내부 ID를 이중 보존하지 않습니다.
			if (!operations.isEmpty() && data.path("additional_evidence").isEmpty()
					&& (data.path("store_results").isMissingNode() || data.path("store_results").isNull()))
				result.put("snapshot_unavailable", true);
		}
		return result;
	}
	private static Map<String, Object> fields(JsonNode value, String... keys) {
		Map<String, Object> fields = new LinkedHashMap<>();
		for (String key : keys) if (value.has(key)) fields.put(key, value.path(key));
		return fields;
	}
}
