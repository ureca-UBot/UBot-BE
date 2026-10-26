package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.SubQuestionFaqs;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReviewContextSnapshotTest {

	@Test
	void 출력_자료에서_하위_질문_원문과_검색어와_FAQ_연결을_보존한다() {
		var parts = List.of(subQuestion("가입 정보 조회", "내 가입 정보 알려줘", 7),
				subQuestion("약정 해지 조건", "약정 해지 조건도 알려줘", 9));
		var materials = AnswerMaterials.builder("내 가입 정보와 약정 해지 조건 알려줘")
				.subQuestions(parts).build();
		var saved = JsonMapper.builder().build().valueToTree(ReviewContextSnapshot.output(materials, null));
		var questions = saved.path("sub_questions");
		assertThat(questions.size()).isEqualTo(2);
		assertThat(questions.get(0).path("userText").asString()).isEqualTo("내 가입 정보 알려줘");
		assertThat(questions.get(0).path("searchQuery").asString()).isEqualTo("가입 정보 조회");
		assertThat(questions.get(0).path("faq_ids").get(0).asLong()).isEqualTo(7);
		assertThat(questions.get(1).path("userText").asString()).isEqualTo("약정 해지 조건도 알려줘");
		assertThat(questions.get(1).path("searchQuery").asString()).isEqualTo("약정 해지 조건");
		assertThat(questions.get(1).path("faq_ids").get(0).asLong()).isEqualTo(9);
		assertThat(questions.get(0).has("faqs")).isFalse();
	}
	@Test
	void 요청_자료에서도_실제_직렬화된_하위_질문의_원문과_FAQ_연결을_보존한다() {
		var json = JsonMapper.builder().build();
		String content = json.writeValueAsString(Map.of("sub_questions",
				List.of(subQuestion("가입 정보 조회", "내 가입 정보 알려줘", 7))));
		var request = new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, content)));
		var question = json.valueToTree(ReviewContextSnapshot.request(request)).path("sub_questions").get(0);
		assertThat(question.path("userText").asString()).isEqualTo("내 가입 정보 알려줘");
		assertThat(question.path("searchQuery").asString()).isEqualTo("가입 정보 조회");
		assertThat(question.path("faq_ids").get(0).asLong()).isEqualTo(7);
		assertThat(question.has("faqs")).isFalse();
	}
	@Test
	void 하위_질문_원문이_없어도_검색어를_원문으로_대체하지_않는다() {
		var materials = AnswerMaterials.builder("가입 정보 알려줘")
				.subQuestions(List.of(subQuestion("가입 정보 조회", null, 7))).build();
		var question = JsonMapper.builder().build().valueToTree(ReviewContextSnapshot.output(materials, null))
				.path("sub_questions").get(0);
		assertThat(question.has("userText")).isTrue();
		assertThat(question.path("userText").isNull()).isTrue();
		assertThat(question.path("searchQuery").asString()).isEqualTo("가입 정보 조회");
		assertThat(question.path("faq_ids").get(0).asLong()).isEqualTo(7);
	}
	private SubQuestionFaqs subQuestion(String searchQuery, String userText, long faqId) {
		var faq = new FaqSearchResponseDto(faqId, "FAQ 질문", "FAQ 답변", 0.9, Intent.GENERAL);
		return new SubQuestionFaqs(searchQuery, List.of(faq), userText);
	}

	@Test
	void 당시_근거는_보존하고_답변복제_금칙어목록_유사도_카드내부ID는_제외한다() {
		String content = """
				{"candidate_answer":"중복 답변","forbidden_words":["내부 사전"],
				"provided_faqs":[{"faqId":7,"question":"조건","answer":"월 5000원","similarityScore":0.9,"variantQuestion":"중복 표현"}],
				"additional_evidence":[{"title":"조회 근거","content":"10월 사용량 2GB"}],
				"history":[{"question":"이전 질문","answer":"이전 답변"}],
				"tool_records":{"user_id":88,"large_card":"화면용 정보"},
				"execution_results":[{"operation":"USER_DATA_LOOKUP","result":{"user_id":88,"large_card":"화면용 정보"}}]}
				""";
		var request = new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, content)));
		String saved = JsonMapper.builder().build().writeValueAsString(ReviewContextSnapshot.request(request));
		assertThat(saved).contains("faqId", "월 5000원", "10월 사용량 2GB", "이전 질문", "USER_DATA_LOOKUP")
				.doesNotContain("candidate_answer", "forbidden_words", "similarityScore", "variantQuestion", "tool_records", "user_id", "large_card");
		assertThat(saved.length()).isLessThan(content.length());
	}
	@Test
	void 카드만_있고_비교할_텍스트근거가_없으면_근거부족을_명시한다() {
		var request = new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER,
				"{\"additional_evidence\":[],\"store_results\":null,\"execution_results\":[{\"operation\":\"USER_DATA_LOOKUP\",\"result\":{\"user_id\":1}}]}")));
		assertThat(ReviewContextSnapshot.request(request)).containsEntry("snapshot_unavailable", true);
	}
	@Test
	void 읽을수없는_자료를_근거가_충분한것으로_기록하지않는다() {
		var request = new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "잘린 자료")));
		assertThat(ReviewContextSnapshot.request(request)).containsEntry("snapshot_unavailable", true);
	}
}
