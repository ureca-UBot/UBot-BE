package com.ubot.notice.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.ubot.notice.service.AdminNoticeService;
import com.ubot.user.repository.UserRepository;

@WebMvcTest(AdminNoticeController.class)
@AutoConfigureMockMvc
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@DisplayName("관리자 공지·장애 Security 테스트")
class AdminNoticeSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminNoticeService adminNoticeService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("인증되지 않은 사용자는 관리자 공지 API에 접근할 수 없다")
    void rejectsUnauthenticatedUser() throws Exception {
        mockMvc.perform(get("/admin/notices")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/notices")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/admin/notices/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일반 사용자는 관리자 공지 API에 접근할 수 없다")
    void rejectsNormalUser() throws Exception {
        mockMvc.perform(get("/admin/notices").with(user("user@test.com").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/notices/1").with(user("user@test.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자는 관리자 공지 API에 접근할 수 있다")
    void allowsAdmin() throws Exception {
        mockMvc.perform(get("/admin/notices/1").with(user("admin@test.com").roles("ADMIN")))
                .andExpect(status().isOk());
    }
}