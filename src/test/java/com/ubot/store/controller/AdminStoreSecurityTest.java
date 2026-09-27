package com.ubot.store.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.ubot.store.service.AdminStoreService;
import com.ubot.user.repository.UserRepository;

@WebMvcTest(AdminStoreController.class)
@AutoConfigureMockMvc
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@DisplayName("관리자 매장 Security 테스트")
class AdminStoreSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminStoreService adminStoreService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("인증되지 않은 사용자는 관리자 매장 API에 접근할 수 없다")
    void rejectsUnauthenticatedUser() throws Exception {
        mockMvc.perform(delete("/admin/stores/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일반 사용자는 관리자 매장 API에 접근할 수 없다")
    void rejectsNormalUser() throws Exception {
        mockMvc.perform(delete("/admin/stores/1").with(user("user@test.com").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자는 관리자 매장 API에 접근할 수 있다")
    void allowsAdmin() throws Exception {
        mockMvc.perform(delete("/admin/stores/1").with(user("admin@test.com").roles("ADMIN")))
            .andExpect(status().isNoContent());
        verify(adminStoreService).deleteStore(1L);
    }
}