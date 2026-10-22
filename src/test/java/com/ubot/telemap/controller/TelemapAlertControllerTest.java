package com.ubot.telemap.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.GlobalExceptionHandler;
import com.ubot.common.PageResponseDto;
import com.ubot.ranking.enums.RegionSido;
import com.ubot.telemap.dto.SurgingQuestionSnapshot;
import com.ubot.telemap.dto.response.SurgingAlertDetailResponse;
import com.ubot.telemap.dto.response.SurgingAlertSummaryResponse;
import com.ubot.telemap.enums.SurgingAlertStatus;
import com.ubot.telemap.service.TelemapAlertService;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("통신 지도 관리자 API")
class TelemapAlertControllerTest {

    private final TelemapAlertService service = mock(TelemapAlertService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TelemapAlertController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("상태·지역·페이지 조건으로 급증 알림 목록을 조회한다")
    void getsAlerts() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        var item = new SurgingAlertSummaryResponse(1L, RegionSido.SEOUL, "5G 문의 급증", 100, 10,
                SurgingAlertStatus.PENDING, null, now, null);
        when(service.getAlerts(SurgingAlertStatus.PENDING, RegionSido.SEOUL, 1, 20))
                .thenReturn(PageResponseDto.of(List.of(item), 1, 20, 21));

        mvc.perform(get("/admin/telemap/alerts")
                        .param("status", "PENDING").param("region", "SEOUL")
                        .param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].alertId").value(1))
                .andExpect(jsonPath("$.data.content[0].subject").value("5G 문의 급증"))
                .andExpect(jsonPath("$.data.totalElements").value(21));
    }

    @Test
    @DisplayName("급증 알림 상세를 조회한다")
    void getsAlertDetail() throws Exception {
        when(service.getAlert(1L)).thenReturn(detail(SurgingAlertStatus.PENDING, null));

        mvc.perform(get("/admin/telemap/alerts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questionSnapshots[0].question").value("질문"));
    }

    @Test
    @DisplayName("인증된 관리자가 급증 알림을 처리 완료한다")
    void marksProcessed() {
        CustomUserDetails administrator = new CustomUserDetails(
                User.builder().id(7L).email("admin@test.invalid").hashedPassword("pw")
                        .role(UserRole.ADMIN).build());
        when(service.markProcessed(1L, 7L)).thenReturn(detail(SurgingAlertStatus.PROCESSED, 7L));

        var response = new TelemapAlertController(service).markProcessed(1L, administrator);

        assertThat(response.data().status()).isEqualTo(SurgingAlertStatus.PROCESSED);
        assertThat(response.data().processedById()).isEqualTo(7L);
        verify(service).markProcessed(1L, 7L);
    }

    private static SurgingAlertDetailResponse detail(SurgingAlertStatus status, Long administratorId) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 23, 12, 0);
        return new SurgingAlertDetailResponse(1L, RegionSido.SEOUL, "제목",
                List.of(new SurgingQuestionSnapshot(2L, "질문", now)), 1, 10, status,
                administratorId, now, status == SurgingAlertStatus.PROCESSED ? now : null);
    }
}
