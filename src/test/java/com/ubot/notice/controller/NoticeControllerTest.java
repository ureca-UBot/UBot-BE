package com.ubot.notice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.chat.util.ClientIpResolver;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.notice.dto.response.NoticeBannerResponseDto;
import com.ubot.notice.dto.response.NoticeResponseDto;
import com.ubot.notice.enums.NoticeType;
import com.ubot.notice.exception.NoticeErrorCode;
import com.ubot.notice.exception.NoticeException;
import com.ubot.notice.service.NoticeService;
import com.ubot.ranking.enums.RegionSido;

@WebMvcTest(NoticeController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("공지·장애 조회 Controller 테스트")
class NoticeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NoticeService noticeService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @MockitoBean
    private IpRegionResolver ipRegionResolver;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @DisplayName("region 파라미터가 있으면 IP 판별 없이 해당 지역으로 배너를 조회한다")
    void usesRegionParameter() throws Exception {
        when(noticeService.getBanners(RegionSido.SEOUL)).thenReturn(banner());

        mockMvc.perform(get("/notices/banners").param("region", "SEOUL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notices[0].noticeId").value(1))
                .andExpect(jsonPath("$.data.outages").isEmpty());

        verify(noticeService).getBanners(RegionSido.SEOUL);
        verifyNoInteractions(clientIpResolver, ipRegionResolver);
    }

    @Test
    @DisplayName("region 파라미터가 없으면 요청 IP로 지역을 판별한다")
    void resolvesRegionFromIp() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("1.2.3.4");
        when(ipRegionResolver.resolve("1.2.3.4")).thenReturn(Optional.of(RegionSido.BUSAN));
        when(noticeService.getBanners(RegionSido.BUSAN)).thenReturn(banner());

        mockMvc.perform(get("/notices/banners")).andExpect(status().isOk());

        verify(noticeService).getBanners(RegionSido.BUSAN);
    }

    @Test
    @DisplayName("IP로 지역을 판별하지 못하면 지역 없이(전체 공지만) 조회한다")
    void usesNullRegionWhenIpIsUnresolved() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("10.0.0.1");
        when(ipRegionResolver.resolve("10.0.0.1")).thenReturn(Optional.empty());
        when(noticeService.getBanners(null)).thenReturn(new NoticeBannerResponseDto(List.of(), List.of()));

        mockMvc.perform(get("/notices/banners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notices").isEmpty());

        verify(noticeService).getBanners(null);
    }

    @Test
    @DisplayName("지원하지 않는 region 값이면 400 응답을 반환한다")
    void rejectsInvalidRegion() throws Exception {
        mockMvc.perform(get("/notices/banners").param("region", "ATLANTIS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G-002"));

        verifyNoInteractions(noticeService);
    }

    @Test
    @DisplayName("목록 조회의 size가 범위를 벗어나면 400 응답을 반환한다")
    void rejectsInvalidSize() throws Exception {
        mockMvc.perform(get("/notices").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G-002"));
    }

    @Test
    @DisplayName("없는 공지를 조회하면 404와 NOTICE-001을 반환한다")
    void returnsNotFound() throws Exception {
        when(noticeService.getNotice(99L)).thenThrow(new NoticeException(NoticeErrorCode.NOTICE_NOT_FOUND));

        mockMvc.perform(get("/notices/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTICE-001"));
    }

    @Test
    @DisplayName("목록 조회는 type과 region 필터를 서비스에 전달한다")
    void passesListFilters() throws Exception {
        mockMvc.perform(get("/notices").param("type", "OUTAGE").param("region", "SEOUL"))
                .andExpect(status().isOk());

        verify(noticeService).getNoticeList(eq(NoticeType.OUTAGE), eq(RegionSido.SEOUL), eq(0), eq(20));
    }

    private NoticeBannerResponseDto banner() {
        NoticeResponseDto notice = new NoticeResponseDto(
                1L, NoticeType.NOTICE, "제목", "내용", null, null, LocalDateTime.of(2026, 1, 1, 0, 0));
        return new NoticeBannerResponseDto(List.of(notice), List.of());
    }
}