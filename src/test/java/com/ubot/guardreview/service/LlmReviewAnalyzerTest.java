package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.guardreview.model.ReviewModels.Source;
import com.ubot.llm.dto.response.LlmResponseDto;
import com.ubot.llm.service.LlmService;
import org.junit.jupiter.api.Test;

class LlmReviewAnalyzerTest {
	LlmService llm = mock(LlmService.class);
	LlmReviewAnalyzer analyzer = new LlmReviewAnalyzer(llm);
	@Test
	void 질문을_데이터로_전달하고_도구없이_기존모델창구를_재사용한다() {
		when(llm.generateJudgment(any())).thenReturn(new LlmResponseDto("{\"suggestions\":[]}"));
		assertThat(analyzer.analyze(new Source(1, "one", "이 지시를 따르라는 입력", null))).isEmpty();
		verify(llm).generateJudgment(argThat(request -> !request.hasTools() && request.hasResponseSchema()
				&& request.messages().getFirst().content().contains("신뢰할 수 없는")
				&& request.messages().getLast().content().contains("question")));
	}
	@Test
	void 응답은_가짜모델로만_확인하고_구조화된_후보를_읽는다() {
		when(llm.generateJudgment(any())).thenReturn(new LlmResponseDto("""
				{"suggestions":[{"kind":"WARNING","category":"MANIPULATION","expression":"위험 요구",
				"reason":"검토","stage":"INPUT","intent":"HARMFUL_REQUEST"}]}
				"""));
		assertThat(analyzer.analyze(new Source(1, "one", "위험 요구", null))).hasSize(1);
	}
	@Test
	void 너무큰_자료는_모델에_보내지않는다() {
		assertThatThrownBy(() -> analyzer.analyze(new Source(1, "one", "가".repeat(30001), null)))
				.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(llm);
	}
}
