package com.ubot.telemap.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.auth.config.JwtAccessDeniedHandler;
import com.ubot.auth.config.JwtAuthenticationEntryPoint;
import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.auth.config.SecurityConfig;
import com.ubot.auth.service.AccessTokenRevocationService;
import com.ubot.auth.util.JwtUtil;
import com.ubot.telemap.service.TelemapAlertService;
import com.ubot.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TelemapAlertController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
@DisplayName("통신 지도 관리자 API 접근 권한")
class TelemapAlertSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean TelemapAlertService telemapAlertService;
    @MockitoBean AccessTokenRevocationService accessTokenRevocationService;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean UserRepository userRepository;

    @Test
    @DisplayName("비로그인 사용자는 접근할 수 없다")
    void rejectsGuest() throws Exception {
        mvc.perform(get("/admin/telemap/alerts")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일반 회원은 접근할 수 없다")
    void rejectsMember() throws Exception {
        mvc.perform(get("/admin/telemap/alerts").with(user("member").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자는 접근할 수 있다")
    void allowsAdministrator() throws Exception {
        mvc.perform(get("/admin/telemap/alerts").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("관리자 API의 페이지와 경로 ID 입력 범위를 검증한다")
    void validatesParameters() throws Exception {
        mvc.perform(get("/admin/telemap/alerts").param("page", "-1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/telemap/alerts").param("size", "101")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/telemap/alerts/0").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }
}
