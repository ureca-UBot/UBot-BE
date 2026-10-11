package com.ubot.notice.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ubot.auth.config.JwtAccessDeniedHandler;
import com.ubot.auth.config.JwtAuthenticationEntryPoint;
import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.auth.config.SecurityConfig;
import com.ubot.auth.util.JwtUtil;
import com.ubot.chat.util.ClientIpResolver;
import com.ubot.chat.util.IpRegionResolver;
import com.ubot.notice.dto.response.NoticeBannerResponseDto;
import com.ubot.notice.service.NoticeService;
import com.ubot.user.repository.UserRepository;

@WebMvcTest(NoticeController.class)
@AutoConfigureMockMvc
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@DisplayName("공지·장애 조회 Security 테스트")
class NoticeSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NoticeService noticeService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @MockitoBean
    private IpRegionResolver ipRegionResolver;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("비회원도 배너를 조회할 수 있다")
    void allowsAnonymousBanner() throws Exception {
        when(noticeService.getBanners(null)).thenReturn(new NoticeBannerResponseDto(List.of(), List.of()));

        mockMvc.perform(get("/notices/banners")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("비회원도 공지 목록을 조회할 수 있다")
    void allowsAnonymousList() throws Exception {
        mockMvc.perform(get("/notices")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET이 아닌 요청은 인증 없이 접근할 수 없다")
    void rejectsAnonymousWrite() throws Exception {
        mockMvc.perform(post("/notices")).andExpect(status().isUnauthorized());
    }
}