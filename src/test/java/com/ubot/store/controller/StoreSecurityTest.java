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
 * 실제 SecurityConfig를 적용해 매장 API가 로그인 사용자에게만 열려 있는지 검증합니다.
 *
 * <p>로그인한 요청의 성공 경로는 다루지 않습니다. WebMvcTest의 MockMvc는 JwtAuthenticationFilter를 보안 필터
 * 체인보다 먼저 한 번 더 실행해서 인증 정보가 지워지므로, 운영 환경과 같은 방식으로 재현할 수 없습니다.
 */
@WebMvcTest(StoreController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@DisplayName("매장 API 로그인 접근 테스트")
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
    @DisplayName("로그인하지 않으면 길찾기를 포함한 매장 API가 401 응답을 반환한다")
    void rejectsAnonymousStoreRequests() throws Exception {
        mockMvc.perform(get("/stores/1/directions")
                        .param("mode", "WALK")
                        .param("latitude", "37.5")
                        .param("longitude", "127.0"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/stores"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/stores/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/stores/nearby")
                        .param("latitude", "37.5")
                        .param("longitude", "127.0"))
                .andExpect(status().isUnauthorized());
    }
}
