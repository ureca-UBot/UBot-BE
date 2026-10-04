package com.ubot.guest.session;

import com.ubot.conversation.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GuestConversationService {
	private final GuestSessionService guestSessionService;
	private final ConversationService conversationService;

	public Long getOrCreateConversationId(HttpServletRequest request) {
		GuestSessionState state = guestSessionService.getOrCreateGuestSession(request);
		synchronized (state) {
			if (state.getConversationId() == null) {
				state.setConversationId(conversationService.createGuestConversation());
			}
			return state.getConversationId();
		}
	}

	public Optional<Long> findConversationId(HttpServletRequest request) {
		return guestSessionService.getGuestSessionState(request).map(GuestSessionState::getConversationId);
	}
}
