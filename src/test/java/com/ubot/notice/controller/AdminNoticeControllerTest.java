package com.ubot.notice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.notice.dto.request.AdminNoticeRequestDto;
import com.ubot.notice.dto.response.AdminNoticeResponseDto;
import com.ubot.notice.dto.response.NoticeResponseDto;
import com.ubot.notice.enums.NoticeType;
import com.ubot.notice.exception.NoticeErrorCode;
import com.ubot.notice.exception.NoticeException;
import com.ubot.notice.service.AdminNoticeService;
import com.ubot.user.entity.User;

@DisplayName("관리자 공지·장애 Controller 테스트")
class AdminNoticeControllerTest {

    private static final String VALID_BODY = """
            {
              "type": "OUTAGE",
              "title": "서울 통신 장애",
              "content": "복구 중입니다.",
              "targetRegion": "SEOUL",
              "endsAt": "2099-01-01T00:00:00"
            }
            """;

    private final AdminNoticeService adminNoticeService = mock(AdminNoticeService.class);
    private final CustomUserDetails administrator = new CustomUserDetails(User.builder().id(7L).build());
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminNoticeController(adminNoticeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == CustomUserDetails.class;
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory factory) {
                        return administrator;
                    }
                })
                .build();
    }

    @Test
    @DisplayName("공지를 등록하면 201 응답과 생성된 자원 위치를 반환한다")
    void createsNotice() throws Exception {
        when(adminNoticeService.createNotice(eq(7L), any(AdminNoticeRequestDto.class))).thenReturn(response(5L));

        mockMvc.perform(post("/admin/notices").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/admin/notices/5"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.detail.noticeId").value(5));
    }

    @Test
    @DisplayName("필수 값이 없거나 제목이 100자를 넘으면 400 응답을 반환한다")
    void rejectsInvalidBody() throws Exception {
        mockMvc.perform(post("/admin/notices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"NOTICE\",\"title\":\" \",\"content\":\"내용\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G-001"));

        mockMvc.perform(post("/admin/notices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"제목\",\"content\":\"내용\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G-001"));

        mockMvc.perform(post("/admin/notices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"NOTICE\",\"title\":\"" + "가".repeat(101) + "\",\"content\":\"내용\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G-001"));

        verifyNoInteractions(adminNoticeService);
    }

    @Test
    @DisplayName("노출 종료 시각이 과거이면 400과 NOTICE-002를 반환한다")
    void rejectsPastEndsAt() throws Exception {
        when(adminNoticeService.createNotice(eq(7L), any(AdminNoticeRequestDto.class)))
                .thenThrow(new NoticeException(NoticeErrorCode.INVALID_ENDS_AT));

        mockMvc.perform(post("/admin/notices").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTICE-002"));
    }

    @Test
    @DisplayName("공지를 수정하면 현재 관리자 ID로 서비스를 호출한다")
    void updatesNotice() throws Exception {
        when(adminNoticeService.updateNotice(eq(7L), eq(5L), any(AdminNoticeRequestDto.class)))
                .thenReturn(response(5L));

        mockMvc.perform(put("/admin/notices/5").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.noticeId").value(5));
    }

    @Test
    @DisplayName("없는 공지를 수정하면 404와 NOTICE-001을 반환한다")
    void returnsNotFoundOnUpdate() throws Exception {
        when(adminNoticeService.updateNotice(eq(7L), eq(99L), any(AdminNoticeRequestDto.class)))
                .thenThrow(new NoticeException(NoticeErrorCode.NOTICE_NOT_FOUND));

        mockMvc.perform(put("/admin/notices/99").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTICE-001"));
    }

    @Test
    @DisplayName("공지를 삭제하면 204 응답을 반환한다")
    void deletesNotice() throws Exception {
        mockMvc.perform(delete("/admin/notices/5")).andExpect(status().isNoContent());

        verify(adminNoticeService).deleteNotice(7L, 5L);
    }

    @Test
    @DisplayName("목록 조회는 type, region, deleted 필터를 서비스에 전달한다")
    void passesListFilters() throws Exception {
        mockMvc.perform(get("/admin/notices")
                .param("type", "OUTAGE").param("region", "SEOUL").param("deleted", "true"))
                .andExpect(status().isOk());

        verify(adminNoticeService).getNoticeList(NoticeType.OUTAGE,
                com.ubot.ranking.enums.RegionSido.SEOUL, true, 0, 20);
    }

    private AdminNoticeResponseDto response(Long noticeId) {
        return new AdminNoticeResponseDto(
                new NoticeResponseDto(noticeId, NoticeType.OUTAGE, "서울 통신 장애", "복구 중입니다.",
                        com.ubot.ranking.enums.RegionSido.SEOUL, LocalDateTime.of(2099, 1, 1, 0, 0),
                        LocalDateTime.of(2026, 1, 1, 0, 0)),
                null);
    }
}