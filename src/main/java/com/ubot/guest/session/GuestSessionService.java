package com.ubot.guest.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class GuestSessionService {
	static final String ATTRIBUTE_NAME = "guestSessionState";
	static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);

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

	public Optional<GuestSessionState> getGuestSessionState(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return Optional.empty();
		}
		Object state = session.getAttribute(ATTRIBUTE_NAME);
		return state instanceof GuestSessionState guest ? Optional.of(guest) : Optional.empty();
	}

	public void clearGuestSession(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
	}
}
