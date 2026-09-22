package com.ubot.user;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.repository.RefreshTokenRepository;
import com.ubot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("사용자 정보 API 통합 테스트")
class UserIntegrationTest {

    private static final String EMAIL = "user-test@example.com";
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void cleanUserData() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("로그인한 사용자는 본인 정보를 조회하고 보낸 항목만 수정한다")
    void getsAndUpdatesMyInfo() throws Exception {
        String accessToken = signUpAndLogin();

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.name").value("테스트사용자"))
                .andExpect(jsonPath("$.data.residenceArea").value("서울특별시"))
                .andExpect(jsonPath("$.data.hashedPassword").doesNotExist());

        mockMvc.perform(patch("/auth/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "  수정된이름  ",
                                  "residenceArea": "부산광역시"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("수정된이름"))
                .andExpect(jsonPath("$.data.residenceArea").value("부산광역시"))
                .andExpect(jsonPath("$.data.birthDate").value("2000-01-01"))
                .andExpect(jsonPath("$.data.gender").value("MALE"));

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$.data.name").value("수정된이름"));
    }

    @Test
    @DisplayName("빈 이름이나 미래 생년월일로 수정하면 400을 반환한다")
    void rejectsInvalidUpdate() throws Exception {
        String accessToken = signUpAndLogin();

        mockMvc.perform(patch("/auth/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "   " }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/auth/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "birthDate": "2999-01-01" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("토큰 없이 내 정보를 조회하면 401을 반환한다")
    void rejectsWithoutAccessToken() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-001"));
    }

    private String signUpAndLogin() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s",
                                  "passwordConfirm": "%s",
                                  "name": "테스트사용자",
                                  "birthDate": "2000-01-01",
                                  "gender": "MALE",
                                  "residenceArea": "서울특별시"
                                }
                                """.formatted(EMAIL, PASSWORD, PASSWORD)))
                .andExpect(status().isOk());

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        Matcher matcher = Pattern.compile("\"accessToken\":\"([^\"]+)\"")
                .matcher(loginResult.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
