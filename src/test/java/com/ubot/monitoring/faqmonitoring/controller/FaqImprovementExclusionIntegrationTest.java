package com.ubot.monitoring.faqmonitoring.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.RedisTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PgvectorTestConfiguration.class, RedisTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FaqImprovementExclusionIntegrationTest {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired UserRepository users;
	@Autowired JwtUtil jwt;
	private String adminToken;
	private long faqId;

	@BeforeEach
	void setup() {
		jdbc.update("delete from faq_improvement_exclusion");
		adminToken = bearer(UserRole.ADMIN);
		Long category = jdbc.queryForObject("insert into faq_category(name) values (?) returning id",
				Long.class, UUID.randomUUID().toString());
		faqId = jdbc.queryForObject("insert into faq(category_id,question,answer) values (?, ?, ?) returning id",
				Long.class, category, "테스트 질문", "테스트 답변");
	}

	@Test
	void frontendCanExcludeListReleaseAndDeleteHistory() throws Exception {
		exclude();
		exclude();
		assertThat(jdbc.queryForObject("select count(*) from faq_improvement_exclusion", Long.class)).isEqualTo(1);
		long id = jdbc.queryForObject("select id from faq_improvement_exclusion", Long.class);
		mvc.perform(get("/admin/faq-monitoring/improvement-exclusions").header("Authorization", adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].faqId").value(faqId))
				.andExpect(jsonPath("$.data.content[0].memo").value("검토 완료"))
				.andExpect(jsonPath("$.data.content[0].outdated").value(false));
		mvc.perform(patch("/admin/faq-monitoring/improvement-exclusions/{id}/release", id)
				.header("Authorization", adminToken).contentType("application/json").content("{}"))
				.andExpect(status().isOk());
		mvc.perform(get("/admin/faq-monitoring/improvement-exclusions").header("Authorization", adminToken))
				.andExpect(jsonPath("$.data.totalElements").value(0));
		mvc.perform(get("/admin/faq-monitoring/improvement-exclusions/history").header("Authorization", adminToken))
				.andExpect(jsonPath("$.data.content[0].exclusionId").value(id))
				.andExpect(jsonPath("$.data.content[0].releasedAt").isNotEmpty());
		mvc.perform(delete("/admin/faq-monitoring/improvement-exclusions/{id}", id).header("Authorization", adminToken))
				.andExpect(status().isOk());
		mvc.perform(get("/admin/faq-monitoring/improvement-exclusions/history").header("Authorization", adminToken))
				.andExpect(jsonPath("$.data.totalElements").value(0));
	}

	@Test
	void deletingActiveExclusionDoesNotCreateReleaseHistoryAndMembersCannotChangeIt() throws Exception {
		exclude();
		long id = jdbc.queryForObject("select id from faq_improvement_exclusion", Long.class);
		mvc.perform(delete("/admin/faq-monitoring/improvement-exclusions/{id}", id)
				.header("Authorization", bearer(UserRole.USER))).andExpect(status().isForbidden());
		mvc.perform(delete("/admin/faq-monitoring/improvement-exclusions/{id}", id).header("Authorization", adminToken))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select count(*) from faq_improvement_exclusion", Long.class)).isZero();
	}

	private void exclude() throws Exception {
		mvc.perform(post("/admin/faq-monitoring/improvements/{id}/exclusions", faqId)
				.header("Authorization", adminToken).contentType("application/json")
				.content("{\"improvementType\":\"LOW_USAGE\",\"memo\":\" 검토 완료 \"}"))
				.andExpect(status().isOk());
	}

	private String bearer(UserRole role) {
		User user = users.saveAndFlush(User.builder().email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("test-hash").name("제외 관리 테스트").role(role)
				.createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
		return "Bearer " + jwt.createAccessToken(user);
	}
}
