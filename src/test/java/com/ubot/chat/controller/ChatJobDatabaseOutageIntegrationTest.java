package com.ubot.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.RedisTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.guest.session.GuestConversationService;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PgvectorTestConfiguration.class, RedisTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatJobDatabaseOutageIntegrationTest {

	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired JwtUtil jwt;
	@Autowired DataSource dataSource;
	@Autowired PostgreSQLContainer postgres;
	@Autowired GuestConversationService guestConversations;
	String authorization;

	@BeforeEach void user() {
		User user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("test-hash").name("조회 장애 테스트").role(UserRole.USER)
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
		authorization = "Bearer " + jwt.createAccessToken(user);
	}

	@Test void pausedDatabaseBoundsAuthenticationAndPreservesInvalidSignatureCheck() throws Exception {
		long originalTimeout = dataSource.unwrap(HikariDataSource.class).getConnectionTimeout();
		postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
		try {
			assertFastUnavailable(true);
			mvc.perform(get("/chat/jobs/1").header("Authorization", authorization + "invalid"))
					.andExpect(status().isUnauthorized());
		} finally {
			postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
		}
		assertThat(dataSource.unwrap(HikariDataSource.class).getConnectionTimeout()).isEqualTo(originalTimeout);
		assertRecovered();
	}

	@Test void blockedStatusQueryIsBoundedForMemberAndGuestAfterAuthentication() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try {
				try (var statement = connection.createStatement()) {
					statement.execute("LOCK TABLE chat_jobs IN ACCESS EXCLUSIVE MODE");
				}
				assertFastUnavailable(true);
				assertFastUnavailable(false);
			} finally { connection.rollback(); }
		}
		assertRecovered();
	}

	@Test void blockedGuestClaimUsesDeadlineWithoutInvalidatingRequestSessionInBackground() throws Exception {
		var initialRequest = new MockHttpServletRequest();
		guestConversations.getOrCreateConversationId(initialRequest);
		var session = (MockHttpSession) initialRequest.getSession();
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try {
				try (var statement = connection.createStatement()) {
					statement.execute("LOCK TABLE conversations IN ACCESS EXCLUSIVE MODE");
				}
				long started = System.nanoTime();
				mvc.perform(get("/chat/jobs/1").session(session).header("Authorization", authorization))
						.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CHAT-025"));
				assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
				assertThat(session.isInvalid()).isFalse();
			} finally { connection.rollback(); }
		}
		mvc.perform(get("/chat/jobs/1").session(session).header("Authorization", authorization))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT-020"));
		assertThat(session.isInvalid()).isTrue();
	}

	private void assertFastUnavailable(boolean member) throws Exception {
		var request = get("/chat/jobs/1");
		if (member) request.header("Authorization", authorization);
		long started = System.nanoTime();
		mvc.perform(request).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.code").value("CHAT-025"));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
	}

	private void assertRecovered() throws Exception {
		mvc.perform(get("/chat/jobs/1").header("Authorization", authorization))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT-020"));
	}
}
