package com.ubot.guest.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

@DisplayName("게스트 세션 서비스 테스트")
class GuestSessionServiceTest {
	private final GuestSessionService service = new GuestSessionService();

	@Test
	@DisplayName("세션이 없으면 30분 만료 세션과 게스트 상태를 만든다")
	void createsSessionForNewGuest() {
		var request = new MockHttpServletRequest();

		GuestSessionState state = service.getOrCreateGuestSession(request);

		assertThat(state).isNotNull();
		assertThat(request.getSession(false)).isNotNull();
		assertThat(request.getSession(false).getMaxInactiveInterval()).isEqualTo(30 * 60);
	}

	@Test
	@DisplayName("같은 세션의 요청은 기존 상태를 그대로 사용한다")
	void reusesExistingState() {
		var session = new MockHttpSession();
		GuestSessionState first = service.getOrCreateGuestSession(requestWith(session));

		var second = requestWith(session);

		assertThat(service.getOrCreateGuestSession(second)).isSameAs(first);
		assertThat(service.getGuestSessionState(second)).containsSame(first);
	}

	@Test
	@DisplayName("세션이 없는 조회는 세션을 만들지 않는다")
	void lookupDoesNotCreateSession() {
		var request = new MockHttpServletRequest();

		assertThat(service.getGuestSessionState(request)).isEmpty();
		service.clearGuestSession(request);

		assertThat(request.getSession(false)).isNull();
	}

	@Test
	@DisplayName("세션을 종료하면 게스트 상태에 접근할 수 없다")
	void clearInvalidatesSession() {
		var session = new MockHttpSession();
		service.getOrCreateGuestSession(requestWith(session));

		service.clearGuestSession(requestWith(session));

		assertThat(session.isInvalid()).isTrue();
		assertThat(service.getGuestSessionState(requestWith(session))).isEmpty();
	}

	@Test
	@DisplayName("세션에는 상태 객체 하나만 저장한다")
	void storesOnlyStateAttribute() {
		var request = new MockHttpServletRequest();
		service.getOrCreateGuestSession(request);

		assertThat(Collections.list(request.getSession(false).getAttributeNames()))
				.containsExactly("guestSessionState");
	}

	private MockHttpServletRequest requestWith(MockHttpSession session) {
		var request = new MockHttpServletRequest();
		request.setSession(session);
		return request;
	}
}
