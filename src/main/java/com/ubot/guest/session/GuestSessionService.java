package com.ubot.guest.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 게스트 상태를 HttpSession에 저장하고 변경합니다. 로그인 사용자 인증(JWT)과는 무관하며,
 * 로그인 정보는 세션에 저장하지 않습니다.
 */
@Service
public class GuestSessionService {
	static final String ATTRIBUTE_NAME = "guestSessionState";
	static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);

	/** 세션이 없으면 새로 만들고(JSESSIONID 발급) 게스트 상태를 반환합니다. 첫 채팅 요청에서만 호출합니다. */
	public GuestSessionState getOrCreateGuestSession(HttpServletRequest request) {
		HttpSession session = request.getSession(true);
		synchronized (session) {
			Object existing = session.getAttribute(ATTRIBUTE_NAME);
			if (existing instanceof GuestSessionState state) {
				return state;
			}
			session.setMaxInactiveInterval((int) SESSION_TIMEOUT.toSeconds());
			GuestSessionState state = new GuestSessionState();
			session.setAttribute(ATTRIBUTE_NAME, state);
			return state;
		}
	}

	/** 세션을 새로 만들지 않고 기존 게스트 상태만 조회합니다. */
	public Optional<GuestSessionState> getGuestSessionState(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return Optional.empty();
		}
		Object state = session.getAttribute(ATTRIBUTE_NAME);
		return state instanceof GuestSessionState guest ? Optional.of(guest) : Optional.empty();
	}

	public Optional<Long> getConversationId(HttpServletRequest request) {
		return getGuestSessionState(request).map(GuestSessionState::getConversationId);
	}

	public void setConversationId(HttpServletRequest request, Long conversationId) {
		getOrCreateGuestSession(request).setConversationId(conversationId);
	}

	public int getChatCount(HttpServletRequest request) {
		return getGuestSessionState(request).map(GuestSessionState::getChatCount).orElse(0);
	}

	public int increaseChatCount(HttpServletRequest request) {
		return getOrCreateGuestSession(request).increaseChatCount();
	}

	/** 세션을 종료하며 게스트 상태가 모두 제거됩니다. 세션이 없으면 아무것도 하지 않습니다. */
	public void clearGuestSession(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
	}
}
