package com.ubot.forbiddenword;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.PgvectorTestConfiguration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// 보안 필터와 JWT를 모두 켠 채로 가입 → 로그인 → 관리자 API → 채팅 차단까지 이어서 확인합니다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("금지어 종단 간(E2E) 테스트")
class ForbiddenWordE2ETest {
	private static final String PASSWORD = "password123";
	private static final String ADMIN_EMAIL = "fw-admin@example.com";
	private static final String USER_EMAIL = "fw-user@example.com";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM forbidden_words");
		jdbcTemplate.update("DELETE FROM answer_attempts_history");
		jdbcTemplate.update("DELETE FROM refresh_tokens");
		jdbcTemplate.update("DELETE FROM users");
	}

	@Test
	@DisplayName("토큰 없이 관리자 금지어 API를 호출하면 401이다")
	void rejectsAnonymous() throws Exception {
		mockMvc.perform(get("/admin/forbidden-words")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/admin/forbidden-words")
						.contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"바보\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("일반 사용자 토큰으로 관리자 금지어 API를 호출하면 403이다")
	void rejectsNormalUser() throws Exception {
		String userToken = signUpAndLogin(USER_EMAIL, false);

		mockMvc.perform(get("/admin/forbidden-words").header("Authorization", "Bearer " + userToken))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/forbidden-words").header("Authorization", "Bearer " + userToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"바보\"}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(patch("/admin/forbidden-words/1").header("Authorization", "Bearer " + userToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"바보\"}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(patch("/admin/forbidden-words/1/status").header("Authorization", "Bearer " + userToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("관리자가 금지어를 등록하면 일반 사용자의 채팅 질문이 400(FW-003)으로 차단된다")
	void adminRegistersWordAndChatIsBlocked() throws Exception {
		String adminToken = signUpAndLogin(ADMIN_EMAIL, true);
		String userToken = signUpAndLogin(USER_EMAIL, false);

		mockMvc.perform(post("/admin/forbidden-words").header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"바보\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("ACTIVE"));

		mockMvc.perform(post("/chat/questions").header("Authorization", "Bearer " + userToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"너 진짜 바보야\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("FW-003"))
				.andExpect(jsonPath("$.message").value("사용할 수 없는 표현이 포함되어 있습니다."));

		// 차단된 질문은 답변 시도 기록조차 남기지 않습니다.
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answer_attempts_history", Integer.class)).isZero();
	}

	@Test
	@DisplayName("관리자가 금지어를 INACTIVE로 바꾸면 같은 질문의 차단이 풀린다")
	void inactiveWordNoLongerBlocks() throws Exception {
		String adminToken = signUpAndLogin(ADMIN_EMAIL, true);
		String userToken = signUpAndLogin(USER_EMAIL, false);

		MvcResult created = mockMvc.perform(post("/admin/forbidden-words").header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"바보\"}"))
				.andExpect(status().isOk()).andReturn();
		long id = Long.parseLong(find(created, "\"id\":(\\d+)"));

		mockMvc.perform(patch("/admin/forbidden-words/" + id + "/status").header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("INACTIVE"));

		// 더 이상 금지어 검사에는 걸리지 않으므로 400(FW-003)이 아니어야 합니다.
		MvcResult chat = mockMvc.perform(post("/chat/questions").header("Authorization", "Bearer " + userToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"너 진짜 바보야\"}"))
				.andReturn();
		assertThat(chat.getResponse().getContentAsString()).doesNotContain("FW-003");
	}

	@Test
	@DisplayName("관리자 목록 조회는 ACTIVE·INACTIVE를 모두 반환하고 잘못된 size는 400이다")
	void adminListsWords() throws Exception {
		String adminToken = signUpAndLogin(ADMIN_EMAIL, true);
		mockMvc.perform(post("/admin/forbidden-words").header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"word\":\"바보\"}")).andExpect(status().isOk());

		mockMvc.perform(get("/admin/forbidden-words").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.length()").value(1))
				.andExpect(jsonPath("$.data.size").value(20));
		mockMvc.perform(get("/admin/forbidden-words").param("size", "30").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isBadRequest());
	}

	private String signUpAndLogin(String email, boolean admin) throws Exception {
		mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"%s","passwordConfirm":"%s","name":"테스트","birthDate":"2000-01-01",
				 "gender":"MALE","residenceArea":"서울특별시"}
				""".formatted(email, PASSWORD, PASSWORD))).andExpect(status().isOk());
		if (admin) {
			// 관리자 승격 API가 없어서 DB에서 직접 role을 바꿉니다. 토큰은 승격 후 로그인해서 받습니다.
			jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
		}
		MvcResult login = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
				.andExpect(status().isOk()).andReturn();
		return find(login, "\"accessToken\":\"([^\"]+)\"");
	}

	private String find(MvcResult result, String regex) throws Exception {
		Matcher matcher = Pattern.compile(regex).matcher(result.getResponse().getContentAsString());
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}
}
