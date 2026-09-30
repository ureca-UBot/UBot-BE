package com.ubot.guest.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.PgvectorTestConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실제 Tomcat과 기존 STATELESS SecurityConfig·JWT 필터를 그대로 거쳐 게스트 세션을 확인합니다.
 * 테스트용 경로는 기존 설정이 비로그인 접근을 허용하는 /stores/** 아래에 두어 보안 설정을 바꾸지 않습니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PgvectorTestConfiguration.class, GuestSessionIntegrationTest.GuestSessionTestController.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("게스트 세션 실서버 통합 테스트")
class GuestSessionIntegrationTest {
	private static final String BASE_PATH = "/stores/guest-session-test";
	private static final String PASSWORD = "password123";

	private final HttpClient client = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Test
	@DisplayName("단순 조회는 세션을 만들지 않고, 게스트 기능을 처음 쓸 때만 JSESSIONID를 발급한다")
	void issuesSessionCookieOnlyOnFirstUse() throws Exception {
		var peek = send(get(BASE_PATH + "/exists"), null);
		assertThat(peek.body()).isEqualTo("false");
		assertThat(sessionCookie(peek)).isEmpty();

		var first = send(post(BASE_PATH + "/start"), null);
		String setCookie = first.headers().allValues("Set-Cookie").stream()
				.filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow();
		assertThat(setCookie).containsIgnoringCase("HttpOnly");
		String cookie = sessionCookie(first).orElseThrow();

		var second = send(post(BASE_PATH + "/start"), cookie);
		assertThat(sessionCookie(second)).as("기존 세션 재사용 시 새 쿠키를 발급하지 않음").isEmpty();
		assertThat(send(get(BASE_PATH + "/exists"), cookie).body()).isEqualTo("true");

		// 쿠키가 없는 다른 게스트는 별도 세션으로 시작합니다.
		var other = send(post(BASE_PATH + "/start"), null);
		assertThat(sessionCookie(other)).isPresent().isNotEqualTo(Optional.of(cookie));
	}

	@Test
	@DisplayName("세션을 종료하면 같은 쿠키로도 이전 상태에 접근할 수 없다")
	void invalidatedSessionLosesState() throws Exception {
		String cookie = sessionCookie(send(post(BASE_PATH + "/start"), null)).orElseThrow();

		send(post(BASE_PATH + "/clear"), cookie);

		var afterClear = send(get(BASE_PATH + "/exists"), cookie);
		assertThat(afterClear.body()).isEqualTo("false");
		assertThat(sessionCookie(afterClear)).isEmpty();
	}

	@Test
	@DisplayName("JWT 로그인 흐름은 세션을 만들지 않고, 게스트 쿠키는 인증 수단이 되지 않는다")
	void jwtFlowIsUnaffected() throws Exception {
		String email = UUID.randomUUID() + "@guest-session.test";
		var signup = send(post("/auth/signup", """
				{"email":"%s","password":"%s","passwordConfirm":"%s","name":"게스트테스트",
				 "birthDate":"2000-01-01","gender":"MALE","residenceArea":"서울특별시"}
				""".formatted(email, PASSWORD, PASSWORD)), null);
		assertThat(signup.statusCode()).isEqualTo(200);
		assertThat(sessionCookie(signup)).isEmpty();

		var login = send(post("/auth/login", """
				{"email":"%s","password":"%s"}
				""".formatted(email, PASSWORD)), null);
		assertThat(login.statusCode()).isEqualTo(200);
		assertThat(sessionCookie(login)).isEmpty();
		Matcher token = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(login.body());
		assertThat(token.find()).isTrue();
		String bearer = "Bearer " + token.group(1);

		var me = send(get("/auth/me").header("Authorization", bearer), null);
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(me.body()).contains(email);
		assertThat(sessionCookie(me)).isEmpty();

		String guestCookie = sessionCookie(send(post(BASE_PATH + "/start"), null)).orElseThrow();
		var meWithBoth = send(get("/auth/me").header("Authorization", bearer), guestCookie);
		assertThat(meWithBoth.statusCode()).isEqualTo(200);
		assertThat(meWithBoth.body()).contains(email);

		var guestOnly = send(get("/auth/me"), guestCookie);
		assertThat(guestOnly.statusCode()).as("게스트 세션만으로는 인증되지 않음").isEqualTo(401);
	}

	private HttpRequest.Builder get(String path) {
		return HttpRequest.newBuilder(uri(path)).GET();
	}

	private HttpRequest.Builder post(String path) {
		return HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody());
	}

	private HttpRequest.Builder post(String path, String json) {
		return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private HttpResponse<String> send(HttpRequest.Builder request, String cookie) throws Exception {
		if (cookie != null) {
			request.header("Cookie", cookie);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** 응답의 Set-Cookie에서 "JSESSIONID=값" 부분만 꺼냅니다. */
	private Optional<String> sessionCookie(HttpResponse<String> response) {
		List<String> cookies = response.headers().allValues("Set-Cookie");
		return cookies.stream()
				.filter(value -> value.startsWith("JSESSIONID="))
				.map(value -> value.split(";", 2)[0])
				.findFirst();
	}

	@RestController
	@RequestMapping(BASE_PATH)
	static class GuestSessionTestController {
		private final GuestSessionService guestSessionService;

		GuestSessionTestController(GuestSessionService guestSessionService) {
			this.guestSessionService = guestSessionService;
		}

		@PostMapping("/start")
		void start(HttpServletRequest request) {
			guestSessionService.getOrCreateGuestSession(request);
		}

		@GetMapping("/exists")
		boolean exists(HttpServletRequest request) {
			return guestSessionService.getGuestSessionState(request).isPresent();
		}

		@PostMapping("/clear")
		void clear(HttpServletRequest request) {
			guestSessionService.clearGuestSession(request);
		}
	}
}
