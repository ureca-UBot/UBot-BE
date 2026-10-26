package com.ubot.counsel;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.RedisTestConfiguration;
import com.ubot.auth.util.JwtUtil;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.session.service.SessionService;
import com.ubot.user.entity.User;
import com.ubot.user.enums.UserRole;
import com.ubot.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PgvectorTestConfiguration.class, RedisTestConfiguration.class})
@Transactional
class CounselCustomerApiTest {
	@Autowired MockMvc mvc;
	@Autowired UserRepository users;
	@Autowired JwtUtil jwt;
	@Autowired SessionService sessions;

	private User user(UserRole role) {
		LocalDateTime now = LocalDateTime.now();
		return users.saveAndFlush(User.builder()
				.name("연결 고객")
				.email(UUID.randomUUID() + "@test.invalid")
				.hashedPassword("not-a-login")
				.role(role)
				.createdAt(now)
				.updatedAt(now)
				.build());
	}

	private ResultActions getAs(User user, String path) throws Exception {
		return mvc.perform(get(path).header("Authorization", "Bearer " + jwt.createAccessToken(user)));
	}

	@Test
	void customerEndpointAllowsOnlyTheAssignedCounselorWhileTheSessionIsOpen() throws Exception {
		User customer = user(UserRole.USER);
		User counselor = user(UserRole.COUNSELOR);
		User otherCounselor = user(UserRole.COUNSELOR);
		sessions.updateAvailability(new Caller(counselor.getId(), UserRole.COUNSELOR), "AVAILABLE");
		var session = sessions.createSession(new Caller(customer.getId(), UserRole.USER));
		String path = "/counsel/sessions/" + session.sessionId() + "/customer";

		// 배정 상담사는 이름과 회선·상품만 받고, 다른 개인정보는 받지 않습니다.
		getAs(counselor, path)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("연결 고객"))
				.andExpect(jsonPath("$.data.lines").isArray())
				.andExpect(jsonPath("$.data.homeProducts").isArray())
				.andExpect(jsonPath("$.data.email").doesNotExist())
				.andExpect(jsonPath("$.data.address").doesNotExist());

		mvc.perform(get(path)).andExpect(status().isUnauthorized());
		getAs(customer, path).andExpect(status().isForbidden());
		getAs(otherCounselor, path).andExpect(status().isForbidden());

		// 상담이 끝나면 배정됐던 상담사도 조회할 수 없습니다.
		sessions.endSession(new Caller(counselor.getId(), UserRole.COUNSELOR), session.sessionId());
		getAs(counselor, path).andExpect(status().isConflict());
	}
}
