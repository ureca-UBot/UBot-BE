package com.ubot.chat.controller;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.RedisTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.chat.job.stream.ChatJobStream;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// test 프로필은 제한을 꺼 두므로 이 클래스에서만 켭니다. 답변 생성은 확인하지 않고 접수만 봅니다.
@SpringBootTest(properties = {
		"app.chat.member-rate-limit.enabled=true",
		"app.chat.member-rate-limit.max-requests=2",
		"app.chat.member-rate-limit.window=60s"})
@Import({PgvectorTestConfiguration.class, RedisTestConfiguration.class})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("회원 채팅 접수 사용자 ID별 제한 통합 테스트")
class ChatMemberRateLimitIntegrationTest {
	@Autowired MockMvc mvc;
	@Autowired JwtUtil jwt;
	@Autowired UserRepository users;
	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;

	@BeforeEach
	void clear() {
		redis.delete(redis.keys("chat:rate:user:*"));
		redis.delete(ChatJobStream.STREAM_KEY);
		redis.delete(redis.keys("chat:jobs:published:*"));
	}

	@Test
	@DisplayName("한도를 넘은 회원은 토큰을 새로 받아도 429 CHAT-024이고, 재시도·재검색도 함께 세며, 다른 회원과 게스트는 영향이 없다")
	void limitsPerUserAcrossTokens() throws Exception {
		User member = saveUser();
		User other = saveUser();

		mvc.perform(question().header("Authorization", bearer(member))).andExpect(status().isAccepted());
		mvc.perform(question().header("Authorization", bearer(member))).andExpect(status().isAccepted());
		mvc.perform(question().header("Authorization", bearer(member)))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("CHAT-024"))
				.andExpect(jsonPath("$.data").doesNotExist());

		// 사용자 ID로 세므로 새로 발급한 토큰도 같은 횟수를 이어 씁니다.
		String reissued = bearer(member);
		mvc.perform(question().header("Authorization", reissued))
				.andExpect(status().isTooManyRequests());
		// 재시도·재검색은 요청 값 검사보다 먼저 세어, 잘못된 키여도 CHAT-011·CHAT-001이 아니라 CHAT-024입니다.
		mvc.perform(post("/chat/questions/retries").header("Idempotency-Key", "x").header("Authorization", reissued))
				.andExpect(jsonPath("$.code").value("CHAT-024"));
		mvc.perform(post("/chat/questions/research").header("Idempotency-Key", "x").header("Authorization", reissued)
						.contentType("application/json").content("{\"intent\":\"GENERAL\"}"))
				.andExpect(jsonPath("$.code").value("CHAT-024"));
		assertThat(attemptCount(member)).isEqualTo(2);

		mvc.perform(question().header("Authorization", bearer(other))).andExpect(status().isAccepted());
		for (int i = 0; i < 3; i++) {
			mvc.perform(question()).andExpect(status().isAccepted());
		}
	}

	private long attemptCount(User user) {
		return jdbc.queryForObject("select count(*) from answer_attempts_history where user_id = ?",
				Long.class, user.getId());
	}

	private MockHttpServletRequestBuilder question() {
		return post("/chat/questions").contentType("application/json").content("{\"question\":\"유심 재발급 방법\"}");
	}

	private String bearer(User target) {
		return "Bearer " + jwt.createAccessToken(target);
	}

	private User saveUser() {
		return users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("test-hash").name("접수 제한 테스트").role(UserRole.USER)
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
	}
}
