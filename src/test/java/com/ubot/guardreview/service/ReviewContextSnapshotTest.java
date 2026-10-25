package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;

import com.ubot.llm.dto.request.LlmMessageRequestDto;
import com.ubot.llm.dto.request.LlmRequestDto;
import com.ubot.llm.enums.LlmMessageRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReviewContextSnapshotTest {

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
	void 읽을수없는_자료를_근거가_충분한것으로_기록하지않는다() {
		var request = new LlmRequestDto(List.of(new LlmMessageRequestDto(LlmMessageRole.USER, "잘린 자료")));
		assertThat(ReviewContextSnapshot.request(request)).containsEntry("snapshot_unavailable", true);
	}
}
