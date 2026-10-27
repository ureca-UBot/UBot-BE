package com.ubot.guest.session;

import com.ubot.conversation.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
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

	public Optional<Long> claimConversation(HttpServletRequest request, Long userId) {
		return claimConversation(request, userId, Supplier::get);
	}

	// DB 승계를 어떻게 실행할지 호출하는 쪽이 정함. 세션 읽기·정리는 요청 스레드에서 함
	public Optional<Long> claimConversation(HttpServletRequest request, Long userId,
			Function<Supplier<Boolean>, Boolean> claimRunner) {
		Optional<Long> conversationId = findConversationId(request);
		if (conversationId.isEmpty()) {
			return Optional.empty();
		}
		boolean claimed = claimRunner.apply(() -> conversationService.claimGuestConversation(conversationId.get(), userId));
		guestSessionService.clearGuestSession(request);
		return claimed ? conversationId : Optional.empty();
	}

	public Optional<Long> findConversationId(HttpServletRequest request) {
		return guestSessionService.getGuestSessionState(request).map(GuestSessionState::getConversationId);
	}
}
