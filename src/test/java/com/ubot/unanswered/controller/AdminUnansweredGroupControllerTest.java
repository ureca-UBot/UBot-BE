package com.ubot.unanswered.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.ubot.unanswered.service.UnansweredEmbeddingBackfillService;
import com.ubot.unanswered.service.UnansweredGroupService;

@DisplayName("관리자 미응답 그룹 컨트롤러 테스트")
class AdminUnansweredGroupControllerTest {

	private final UnansweredGroupService unansweredGroupService =
			mock(UnansweredGroupService.class);

	private final UnansweredEmbeddingBackfillService unansweredEmbeddingBackfillService =
			mock(UnansweredEmbeddingBackfillService.class);

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		AdminUnansweredGroupController controller =
				new AdminUnansweredGroupController(
						unansweredGroupService,
						unansweredEmbeddingBackfillService
				);

		mockMvc = MockMvcBuilders
				.standaloneSetup(controller)
				.build();
	}

	@Test
	@DisplayName("현재 임베딩 프로필의 미응답 질문 백필을 실행하고 처리 건수를 반환한다")
	void backfillUnansweredEmbeddings_returnsUpdatedCount() throws Exception {
		when(unansweredEmbeddingBackfillService.backfillCurrentProfile())
				.thenReturn(5);

		mockMvc.perform(
						post("/admin/unanswered-groups/embeddings/backfill")
				)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data").value(5));

		verify(unansweredEmbeddingBackfillService)
				.backfillCurrentProfile();
	}
}
