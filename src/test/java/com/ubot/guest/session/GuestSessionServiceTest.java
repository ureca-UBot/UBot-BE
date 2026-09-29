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
	@DisplayName("세션이 없으면 새 세션과 초기 상태를 만든다")
	void createsSessionForNewGuest() {
		var request = new MockHttpServletRequest();

		GuestSessionState state = service.getOrCreateGuestSession(request);

		assertThat(request.getSession(false)).isNotNull();
		assertThat(state.getChatCount()).isZero();
		assertThat(state.getConversationId()).isNull();
		assertThat(request.getSession(false).getMaxInactiveInterval()).isEqualTo(30 * 60);
	}

	@Test
	@DisplayName("같은 세션의 요청은 기존 상태를 유지한다")
	void reusesExistingState() {
		var session = new MockHttpSession();
		var first = requestWith(session);
		service.increaseChatCount(first);
		service.setConversationId(first, 100L);

		var second = requestWith(session);
		GuestSessionState state = service.getOrCreateGuestSession(second);

		assertThat(state.getChatCount()).isEqualTo(1);
		assertThat(state.getConversationId()).isEqualTo(100L);
		assertThat(service.getGuestSessionState(second)).containsSame(state);
	}

	@Test
	@DisplayName("질문 횟수를 증가시키고 조회한다")
	void increasesChatCount() {
		var request = new MockHttpServletRequest();
		service.increaseChatCount(request);

		assertThat(service.increaseChatCount(request)).isEqualTo(2);
		assertThat(service.getChatCount(request)).isEqualTo(2);
	}

	@Test
	@DisplayName("대화 식별자를 저장하고 조회한다")
	void storesConversationId() {
		var request = new MockHttpServletRequest();
		assertThat(service.getOrCreateGuestSession(request).getConversationId()).isNull();

		service.setConversationId(request, 100L);

		assertThat(service.getConversationId(request)).contains(100L);
	}

	@Test
	@DisplayName("세션이 없는 조회는 세션을 만들지 않는다")
	void lookupDoesNotCreateSession() {
		var request = new MockHttpServletRequest();

		assertThat(service.getGuestSessionState(request)).isEmpty();
		assertThat(service.getConversationId(request)).isEmpty();
		assertThat(service.getChatCount(request)).isZero();
		service.clearGuestSession(request);

		assertThat(request.getSession(false)).isNull();
	}

	@Test
	@DisplayName("세션을 종료하면 게스트 상태에 접근할 수 없다")
	void clearInvalidatesSession() {
		var session = new MockHttpSession();
		service.increaseChatCount(requestWith(session));

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
