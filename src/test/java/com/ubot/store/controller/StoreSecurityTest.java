package com.ubot.store.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ubot.auth.config.JwtAccessDeniedHandler;
import com.ubot.auth.config.JwtAuthenticationEntryPoint;
import com.ubot.auth.config.JwtAuthenticationFilter;
import com.ubot.auth.config.SecurityConfig;
import com.ubot.auth.util.JwtUtil;
import com.ubot.direction.service.DirectionsService;
import com.ubot.store.service.StoreService;
import com.ubot.user.repository.UserRepository;

/**
 * 실제 SecurityConfig를 적용해 매장 API가 로그인하지 않은 사용자에게도 열려 있는지 검증합니다.
 */
@WebMvcTest(StoreController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@DisplayName("매장 API 비로그인 접근 테스트")
class StoreSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private DirectionsService directionsService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("로그인하지 않아도 길찾기를 포함한 매장 API에 접근할 수 있다")
    void allowsAnonymousStoreRequests() throws Exception {
        mockMvc.perform(get("/stores/1/directions")
                        .param("mode", "WALK")
                        .param("latitude", "37.5")
                        .param("longitude", "127.0"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/stores"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/stores/1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/stores/nearby")
                        .param("latitude", "37.5")
                        .param("longitude", "127.0"))
                .andExpect(status().isOk());
    }
}
