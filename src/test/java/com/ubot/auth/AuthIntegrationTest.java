package com.ubot.auth;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.repository.RefreshTokenRepository;
import com.ubot.auth.service.AccessTokenRevocationService;
import com.ubot.auth.service.LoginAttemptService;
import com.ubot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("인증 API 통합 테스트")
class AuthIntegrationTest {

    private static final String EMAIL = "auth-test@example.com";
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private LoginAttemptService loginAttemptService;

    @MockitoBean
    private AccessTokenRevocationService accessTokenRevocationService;

    @BeforeEach
    void cleanAuthenticationData() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("회원가입부터 로그아웃까지 access token과 refresh token 흐름을 검증한다")
    void signsUpLogsInRefreshesAndLogsOut() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupRequest(EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginRequest(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();

        String accessToken = responseToken(loginResult, "accessToken");
        String firstRefreshToken = responseToken(loginResult, "refreshToken");

        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequest(firstRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        String rotatedRefreshToken = responseToken(refreshResult, "refreshToken");
        assertThat(rotatedRefreshToken).isNotEqualTo(firstRefreshToken);

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequest(firstRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("JWT-001"));

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequest(rotatedRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("JWT-001"));
    }

    @Test
    @DisplayName("refresh token은 원문이 아닌 SHA-256 해시로 저장한다")
    void storesRefreshTokenAsHash() throws Exception {
        String refreshToken = signUpAndLogin();

        String storedToken = refreshTokenRepository.findAll().get(0).getToken();
        assertThat(storedToken).isNotEqualTo(refreshToken).hasSize(64);
    }

    @Test
    @DisplayName("교체 후 유예 시간이 지난 refresh token이 다시 오면 사용자의 토큰을 모두 무효화한다")
    void revokesTokensWhenRotatedTokenIsReused() throws Exception {
        String firstRefreshToken = signUpAndLogin();
        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequest(firstRefreshToken)))
                .andExpect(status().isOk())
                .andReturn();
        String rotatedRefreshToken = responseToken(refreshResult, "refreshToken");
        jdbcTemplate.update("UPDATE refresh_tokens SET rotated_at = rotated_at - INTERVAL '11 seconds'");

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequest(firstRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("JWT-008"));

        assertThat(refreshTokenRepository.findAll()).isEmpty();
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequest(rotatedRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("JWT-001"));
    }

    @Test
    @DisplayName("로그아웃하면 사용자의 접근 토큰 차단을 기록하고, 차단된 접근 토큰은 JWT-009로 거부한다")
    void revokesAccessTokenOnLogout() throws Exception {
        signUpAndLogin();
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginRequest(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = responseToken(loginResult, "accessToken");
        Long userId = userRepository.findAll().get(0).getId();

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        verify(accessTokenRevocationService).revokeAll(userId);

        when(accessTokenRevocationService.isRevoked(eq(userId), any())).thenReturn(true);
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("JWT-009"));
    }

    @Test
    @DisplayName("refresh token 재사용을 감지하면 접근 토큰도 차단한다")
    void revokesAccessTokensWhenRefreshTokenIsReused() throws Exception {
        String firstRefreshToken = signUpAndLogin();
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequest(firstRefreshToken)))
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE refresh_tokens SET rotated_at = rotated_at - INTERVAL '11 seconds'");

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshRequest(firstRefreshToken)))
                .andExpect(jsonPath("$.code").value("JWT-008"));

        verify(accessTokenRevocationService).revokeAll(userRepository.findAll().get(0).getId());
    }

    @Test
    @DisplayName("잘못된 이메일 형식의 회원가입 요청을 400으로 거부한다")
    void rejectsInvalidSignupEmail() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupRequest("not-an-email")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER-007"));
    }

    @Test
    @DisplayName("토큰 없이 로그아웃을 요청하면 통일된 401 응답을 반환한다")
    void rejectsLogoutWithoutAccessToken() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-001"));
    }

    @Test
    @DisplayName("위조된 access token으로 로그아웃을 요청하면 통일된 401 응답을 반환한다")
    void rejectsInvalidAccessToken() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("JWT-004"));
    }

    private String signUpAndLogin() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupRequest(EMAIL)))
                .andExpect(status().isOk());
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginRequest(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return responseToken(loginResult, "refreshToken");
    }

    private String responseToken(MvcResult result, String tokenName) throws Exception {
        Pattern tokenPattern = Pattern.compile("\\\"" + tokenName + "\\\":\\\"([^\\\"]+)\\\"");
        Matcher matcher = tokenPattern.matcher(result.getResponse().getContentAsString());

        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String signupRequest(String email) {
        return """
                {
                  "email": "%s",
                  "password": "%s",
                  "passwordConfirm": "%s",
                  "name": "인증테스트사용자",
                  "birthDate": "2000-01-01",
                  "gender": "MALE",
                  "residenceArea": "서울특별시"
                }
                """.formatted(email, PASSWORD, PASSWORD);
    }

    private String loginRequest(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password);
    }

    private String refreshRequest(String refreshToken) {
        return """
                {
                  "refreshToken": "%s"
                }
                """.formatted(refreshToken);
    }
}
