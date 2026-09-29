package com.ubot.forbiddenword.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.common.PageResponseDto;
import com.ubot.common.GlobalException;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.forbiddenword.dto.request.ForbiddenWordCreateRequestDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordStatusUpdateRequestDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordUpdateRequestDto;
import com.ubot.forbiddenword.dto.response.ForbiddenWordResponseDto;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.exception.ForbiddenWordException;
import com.ubot.forbiddenword.service.ForbiddenWordService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminForbiddenWordController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("관리자 금지어 Controller 테스트")
class AdminForbiddenWordControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ForbiddenWordService forbiddenWordService;

	@MockitoBean
	private JwtAuthenticationFilter jwtAuthenticationFilter;

	@Test
	@DisplayName("금지어를 등록하면 생성된 금지어를 반환한다")
	void createsForbiddenWord() throws Exception {
		when(forbiddenWordService.createForbiddenWord(any(ForbiddenWordCreateRequestDto.class)))
				.thenReturn(response(1L, "바보", ForbiddenWordStatus.ACTIVE));

		mockMvc.perform(post("/admin/forbidden-words")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"word\": \"바보\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").value(1))
				.andExpect(jsonPath("$.data.word").value("바보"))
				.andExpect(jsonPath("$.data.status").value("ACTIVE"))
				.andExpect(jsonPath("$.data.createdAt").doesNotExist());
	}

	@Test
	@DisplayName("등록 시 word가 비어 있으면 400을 반환한다")
	void rejectsBlankWordOnCreate() throws Exception {
		mockMvc.perform(post("/admin/forbidden-words")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"word\": \"  \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(CommonErrorCode.INVALID_INPUT.getCode()));
		verifyNoInteractions(forbiddenWordService);
	}

	@Test
	@DisplayName("등록 시 word가 100자를 넘으면 400을 반환한다")
	void rejectsTooLongWordOnCreate() throws Exception {
		mockMvc.perform(post("/admin/forbidden-words")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"word\": \"" + "가".repeat(101) + "\"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(forbiddenWordService);
	}

	@Test
	@DisplayName("중복 금지어 등록은 409를 반환한다")
	void returnsConflictWhenDuplicated() throws Exception {
		when(forbiddenWordService.createForbiddenWord(any(ForbiddenWordCreateRequestDto.class)))
				.thenThrow(new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_EXIST));

		mockMvc.perform(post("/admin/forbidden-words")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"word\": \"바보\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("FW-002"));
	}

	@Test
	@DisplayName("금지어 목록은 기본 페이지 크기 20으로 조회한다")
	void getsListWithDefaultSize() throws Exception {
		when(forbiddenWordService.getForbiddenWordList(0, 20)).thenReturn(PageResponseDto.of(
				List.of(response(2L, "욕설", ForbiddenWordStatus.INACTIVE), response(1L, "바보", ForbiddenWordStatus.ACTIVE)),
				0, 20, 2));

		mockMvc.perform(get("/admin/forbidden-words"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.length()").value(2))
				.andExpect(jsonPath("$.data.content[0].status").value("INACTIVE"))
				.andExpect(jsonPath("$.data.size").value(20))
				.andExpect(jsonPath("$.data.totalElements").value(2));
	}

	@Test
	@DisplayName("page와 size를 지정해 금지어 목록을 조회한다")
	void getsListWithPageAndSize() throws Exception {
		when(forbiddenWordService.getForbiddenWordList(1, 50)).thenReturn(PageResponseDto.of(List.of(), 1, 50, 0));

		mockMvc.perform(get("/admin/forbidden-words").param("page", "1").param("size", "50"))
				.andExpect(status().isOk());
		verify(forbiddenWordService).getForbiddenWordList(1, 50);
	}

	@Test
	@DisplayName("허용되지 않는 size는 400을 반환한다")
	void rejectsInvalidSize() throws Exception {
		when(forbiddenWordService.getForbiddenWordList(eq(0), eq(30)))
				.thenThrow(new GlobalException(CommonErrorCode.INVALID_PARAMETER));

		mockMvc.perform(get("/admin/forbidden-words").param("size", "30"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(CommonErrorCode.INVALID_PARAMETER.getCode()));
	}

	@Test
	@DisplayName("음수 page는 400을 반환한다")
	void rejectsNegativePage() throws Exception {
		mockMvc.perform(get("/admin/forbidden-words").param("page", "-1"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(forbiddenWordService);
	}

	@Test
	@DisplayName("금지어 단어를 수정하면 수정된 금지어를 반환한다")
	void updatesForbiddenWord() throws Exception {
		when(forbiddenWordService.updateForbiddenWord(eq(1L), any(ForbiddenWordUpdateRequestDto.class)))
				.thenReturn(response(1L, "멍청이", ForbiddenWordStatus.ACTIVE));

		mockMvc.perform(patch("/admin/forbidden-words/1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"word\": \"멍청이\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.word").value("멍청이"));
		verify(forbiddenWordService).updateForbiddenWord(1L, new ForbiddenWordUpdateRequestDto("멍청이"));
	}

	@Test
	@DisplayName("금지어 상태만 수정하면 word 없이 상태가 바뀐다")
	void updatesForbiddenWordStatus() throws Exception {
		when(forbiddenWordService.updateForbiddenWordStatus(eq(1L), any(ForbiddenWordStatusUpdateRequestDto.class)))
				.thenReturn(response(1L, "바보", ForbiddenWordStatus.INACTIVE));

		mockMvc.perform(patch("/admin/forbidden-words/1/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\": \"INACTIVE\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.word").value("바보"))
				.andExpect(jsonPath("$.data.status").value("INACTIVE"));
		verify(forbiddenWordService).updateForbiddenWordStatus(1L,
				new ForbiddenWordStatusUpdateRequestDto(ForbiddenWordStatus.INACTIVE));
	}

	@Test
	@DisplayName("상태 수정 시 status가 없으면 400(FW-005)을 반환한다")
	void rejectsMissingStatus() throws Exception {
		when(forbiddenWordService.updateForbiddenWordStatus(eq(1L), any(ForbiddenWordStatusUpdateRequestDto.class)))
				.thenThrow(new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_STATUS_REQUIRED));

		mockMvc.perform(patch("/admin/forbidden-words/1/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("FW-005"));
	}

	@Test
	@DisplayName("상태 수정 시 알 수 없는 status 값은 400을 반환한다")
	void rejectsUnknownStatus() throws Exception {
		mockMvc.perform(patch("/admin/forbidden-words/1/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\": \"DELETED\"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(forbiddenWordService);
	}

	@Test
	@DisplayName("상태 수정 시 id가 양수가 아니면 400을 반환한다")
	void rejectsNonPositiveIdOnStatus() throws Exception {
		mockMvc.perform(patch("/admin/forbidden-words/0/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\": \"ACTIVE\"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(forbiddenWordService);
	}

	@Test
	@DisplayName("존재하지 않는 금지어 수정은 404를 반환한다")
	void returnsNotFoundOnUpdate() throws Exception {
		when(forbiddenWordService.updateForbiddenWord(eq(9L), any(ForbiddenWordUpdateRequestDto.class)))
				.thenThrow(new ForbiddenWordException(ForbiddenWordErrorCode.FORBIDDEN_WORD_NOT_FOUND));

		mockMvc.perform(patch("/admin/forbidden-words/9")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"word\": \"멍청이\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("FW-001"));
	}

	@Test
	@DisplayName("수정 시 id가 양수가 아니면 400을 반환한다")
	void rejectsNonPositiveId() throws Exception {
		mockMvc.perform(patch("/admin/forbidden-words/0")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"word\": \"멍청이\"}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(forbiddenWordService);
	}

	private ForbiddenWordResponseDto response(Long id, String word, ForbiddenWordStatus status) {
		return new ForbiddenWordResponseDto(id, word, status, LocalDateTime.of(2026, 9, 29, 12, 0));
	}
}
