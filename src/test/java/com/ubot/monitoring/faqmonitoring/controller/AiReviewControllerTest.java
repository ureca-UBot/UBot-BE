package com.ubot.monitoring.faqmonitoring.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.monitoring.faqmonitoring.dto.request.DuplicateSuspectedReviewRequest;
import com.ubot.monitoring.faqmonitoring.dto.response.AiReviewResponse;
import com.ubot.monitoring.faqmonitoring.service.AiReviewService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiReviewControllerTest {

	private final AiReviewService aiReviewService = org.mockito.Mockito.mock(AiReviewService.class);
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new AiReviewController(aiReviewService)).build();
	}

	@Test
	@DisplayName("중복 의심 AI 검토 API는 후보 FAQ ID와 대상 FAQ ID를 서비스에 전달한다")
	void 중복_의심_검토는_후보_ID를_서비스에_전달하고_권고와_세부사항을_반환한다() throws Exception {
		var request = new DuplicateSuspectedReviewRequest(List.of(10L, 20L));
		when(aiReviewService.reviewDuplicateSuspected(1L, request))
				.thenReturn(new AiReviewResponse("- **FAQ 10**: 중복 판정", "## 세부사항"));

		mockMvc.perform(post("/admin/faq-monitoring/improvements/{faqId}/ai-review/duplicate-suspected", 1L)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"candidateFaqIds\":[10,20]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.recommendation").value("- **FAQ 10**: 중복 판정"))
				.andExpect(jsonPath("$.data.details").value("## 세부사항"));

		verify(aiReviewService).reviewDuplicateSuspected(1L, request);
	}

	@Test
	@DisplayName("검색 품질 낮음 AI 검토 API는 대상 FAQ ID를 서비스에 전달한다")
	void 검색품질_낮음_검토는_FAQ_ID만_서비스에_전달한다() throws Exception {
		when(aiReviewService.reviewLowRagQuality(30L))
				.thenReturn(new AiReviewResponse("수정 불필요", "## 검색 품질 낮음 FAQ 판정 결과"));

		mockMvc.perform(post("/admin/faq-monitoring/improvements/{faqId}/ai-review/low-rag-quality", 30L))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.recommendation").value("수정 불필요"))
				.andExpect(jsonPath("$.data.details").value("## 검색 품질 낮음 FAQ 판정 결과"));

		verify(aiReviewService).reviewLowRagQuality(30L);
	}
}
