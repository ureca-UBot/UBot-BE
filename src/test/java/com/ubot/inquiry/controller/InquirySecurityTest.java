package com.ubot.inquiry.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.auth.config.JwtAccessDeniedHandler;
import com.ubot.auth.config.JwtAuthenticationEntryPoint;
import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.auth.config.SecurityConfig;
import com.ubot.auth.service.AccessTokenRevocationService;
import com.ubot.auth.util.JwtUtil;
import com.ubot.inquiry.service.InquiryService;
import com.ubot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({InquiryController.class, AdminInquiryController.class})
@AutoConfigureMockMvc
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@DisplayName("고객문의 접근 권한 테스트")
class InquirySecurityTest {
    @Autowired MockMvc mockMvc;

    @MockitoBean InquiryService inquiryService;
    @MockitoBean AccessTokenRevocationService accessTokenRevocationService;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean UserRepository userRepository;

    @Test
    @DisplayName("비로그인 사용자는 회원 및 관리자 문의 API에 접근할 수 없다")
    void guestCannotAccessMemberOrAdminInquiries() throws Exception {
        mockMvc.perform(get("/inquiries")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/inquiries")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/inquiries")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일반 회원은 관리자 문의 API에 접근할 수 없다")
    void memberCannotAccessAdminInquiries() throws Exception {
        mockMvc.perform(get("/admin/inquiries").with(user("member").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자는 관리자 문의 목록을 조회할 수 있다")
    void administratorCanAccessAdminInquiryList() throws Exception {
        mockMvc.perform(get("/admin/inquiries").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }
}
