package com.ubot.auth;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.auth.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.cors.allowed-origins=https://allowed.example, https://second.example")
@AutoConfigureMockMvc
@Import(PgvectorTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("CORS 허용 origin 테스트")
class CorsIntegrationTest {
	@Autowired MockMvc mvc;

	@Test
	@DisplayName("설정한 origin의 사전 요청은 허용하고 인증 헤더와 쿠키 전달을 허용한다")
	void allowsConfiguredOrigin() throws Exception {
		mvc.perform(options("/auth/login")
						.header("Origin", "https://second.example")
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "Authorization, Content-Type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "https://second.example"))
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"));
	}

	@Test
	@DisplayName("와일드카드 origin은 설정 단계에서 거부한다")
	void rejectsWildcardOrigin() {
		assertThatThrownBy(() -> new SecurityConfig(null, null, null).corsConfigurationSource("https://ok.example, *"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("설정하지 않은 origin의 사전 요청은 거절한다")
	void rejectsOtherOrigin() throws Exception {
		mvc.perform(options("/auth/login")
						.header("Origin", "https://evil.example")
						.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}
}
