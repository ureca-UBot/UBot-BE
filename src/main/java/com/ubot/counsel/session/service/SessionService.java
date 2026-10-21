package com.ubot.counsel.session.service;

import com.ubot.conversation.service.ConversationService;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.counsel.session.dto.request.HandoffRequestDto;
import com.ubot.counsel.session.dto.response.HistoryMessageResponseDto;
import com.ubot.counsel.session.entity.Session;
import com.ubot.counsel.session.repository.HistoryRepository;
import com.ubot.counsel.session.repository.SessionRepository;
import com.ubot.user.enums.UserRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class SessionService {
	private final SessionRepository repository;
	private final TransactionTemplate tx;
	private final CounselProperties config;
	private final ConversationService conversations;
	private final HistoryRepository history;
	private final HandoffHistoryService handoffHistory;
	public Session createHandoff(Caller caller, HandoffRequestDto request) {
		caller.require(UserRole.USER);
		Session retry = existingHandoff(caller, request);
		if (retry != null) return retry;
		if (request.previousSessionId() != null) {
			var previous = repository.get(request.previousSessionId(), false);
			if (previous.customerId() != caller.id()) throw CounselException.forbidden();
			if (!previous.status().equals("ENDED") || (request.conversationId() != null
					&& !Objects.equals(previous.conversationId(), request.conversationId()))) {
				throw CounselException.conflict("종료된 같은 대화에서만 상담을 이어갈 수 있습니다.");
			}
			request = new HandoffRequestDto(request.handoffId(), previous.conversationId(),
					request.previousSessionId(), request.faqIds());
		}
		// 소유권을 확인한 저장 문답과 서버에서 조회한 FAQ만 인계함
		var snapshot = handoffHistory.load(caller.id(), request.conversationId(), request.faqIds());
		var resolved = request;
		return lifecycle(() -> {
			snapshot.addAll(history.previousCounsel(caller.id(), resolved.conversationId()));
			return createWithHistory(caller, resolved, snapshot);
		});
	}
	private Session createWithHistory(Caller caller, HandoffRequestDto request, List<HistoryMessageResponseDto> snapshot) {
		var existing = existingHandoff(caller, request);
		if (existing != null) return existing;
		UUID conversationId = conversations.getOrCreateMemberConversation(caller.id(), request.conversationId()).getPublicId();
		List<HistoryMessageResponseDto> numbered = new ArrayList<>();
		for (var item : history.reuseSavedOrigins(conversationId, snapshot)) {
			numbered.add(new HistoryMessageResponseDto(numbered.size() + 1L,
					item.role(), item.content(), item.source(), item.originSessionId(), item.originSequence()));
		}
		UUID id = repository.createHandoff(caller.id(), conversationId, request.handoffId());
		history.save(id, numbered);
		repository.assign(config.heartbeatSeconds());
		return repository.get(id, false);
	}
	private Session existingHandoff(Caller caller, HandoffRequestDto request) {
		var prior = repository.handoff(request.handoffId());
		if (prior != null) {
			if (prior.customerId() != caller.id()) throw CounselException.forbidden();
			if (request.conversationId() != null && !Objects.equals(prior.conversationId(), request.conversationId())) {
				throw CounselException.conflict("같은 상담 요청 ID로 대화를 바꿀 수 없습니다.");
			}
			return prior;
		}
		var current = repository.current(caller.id(), false);
		if (current != null && request.conversationId() != null && !Objects.equals(current.conversationId(), request.conversationId())) {
			throw CounselException.conflict("진행 중인 상담을 먼저 종료해 주세요.");
		}
		return current;
	}
	public Session getConversationSession(Caller caller, UUID conversationId) {
		caller.require(UserRole.USER);
		return repository.latest(caller.id(), conversationId);
	}
	public List<HistoryMessageResponseDto> getHistory(Caller caller, UUID sessionId, long after, int limit) {
		if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException();
		return tx.execute(ignored -> {
			var session = repository.get(sessionId, true);
			session.requireParticipant(caller);
			return history.get(sessionId, after, limit);
		});
	}
	public Session createSession(Caller caller) {
		return createHandoff(caller, new HandoffRequestDto(UUID.randomUUID(), null, List.of()));
	}
	public Session getCurrentSession(Caller caller) {
		if (!(caller.role() == UserRole.USER)) caller.require(UserRole.COUNSELOR);
		return repository.current(caller.id(), (caller.role() == UserRole.COUNSELOR));
	}
	public Session getSession(Caller caller, UUID id) {
		var session = repository.get(id, false);
		session.requireParticipant(caller);
		return session;
	}
	public String updateAvailability(Caller caller, String status) {
		caller.require(UserRole.COUNSELOR);
		if (!status.equals("AVAILABLE") && !status.equals("OFFLINE")) throw new IllegalArgumentException();
		return lifecycle(() -> {
			repository.presence(caller.id(), status);
			repository.maintain(config.heartbeatSeconds(), config.reconnectSeconds());
			repository.assign(config.heartbeatSeconds());
			return repository.presenceStatus(caller.id());
		});
	}
	public String updateHeartbeat(Caller caller) {
		caller.require(UserRole.COUNSELOR);
		return lifecycle(() -> {
			repository.heartbeat(caller.id());
			repository.maintain(config.heartbeatSeconds(), config.reconnectSeconds());
			repository.assign(config.heartbeatSeconds());
			return repository.presenceStatus(caller.id());
		});
	}
	public Session endSession(Caller caller, UUID id) {
		return lifecycle(() -> {
			var session = repository.get(id, true);
			session.requireParticipant(caller);
			repository.end(id);
			repository.assign(config.heartbeatSeconds());
			return repository.get(id, false);
		});
	}
	private <T> T lifecycle(Supplier<T> operation) {
		return tx.execute(ignored -> {
			repository.lockQueue();
			repository.maintain(config.heartbeatSeconds(), config.reconnectSeconds());
			return operation.get();
		});
	}
	public void maintainSessions() {
		if (!config.enabled()) return;
		try {
			lifecycle(() -> { repository.assign(config.heartbeatSeconds()); return null; });
		} catch (org.springframework.dao.DataAccessException exception) {
			log.error("상담 배정 정리 실패 code=COUNSEL-011 exception={}", exception.getClass().getSimpleName());
		}
	}
}
