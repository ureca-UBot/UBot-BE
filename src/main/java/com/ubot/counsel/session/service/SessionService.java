package com.ubot.counsel.session.service;

import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.conversation.service.ConversationService;
import com.ubot.counsel.session.dto.request.HandoffRequestDto;
import com.ubot.counsel.session.dto.response.HistoryMessageResponseDto;
import com.ubot.counsel.session.entity.Session;
import com.ubot.counsel.session.repository.HistoryRepository;
import com.ubot.counsel.session.repository.SessionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionService {
	private final SessionRepository repository;
	private final TransactionTemplate tx;
	private final CounselProperties config;
	private final ConversationService conversations;
	private final HistoryRepository history;
	public SessionService(SessionRepository repository, TransactionTemplate tx, CounselProperties config,
			ConversationService conversations, HistoryRepository history) {
		this.repository = repository;
		this.tx = tx; this.config = config;
		this.conversations = conversations; this.history = history;
	}
	public Session createHandoff(Caller caller, HandoffRequestDto request) {
		caller.require("USER");
		Session retry = existingHandoff(caller, request);
		if (retry != null) return retry;
		if (request.previousSessionId() != null) {
			return lifecycle(() -> {
				var previous = repository.get(request.previousSessionId(), true);
				if (previous.customerId() != caller.id()) throw CounselException.forbidden();
				if (!previous.status().equals("ENDED") || (request.conversationId() != null
						&& !Objects.equals(previous.conversationId(), request.conversationId()))) {
					throw CounselException.conflict("종료된 같은 대화에서만 상담을 이어갈 수 있습니다.");
				}
				var snapshot = new ArrayList<>(history.get(previous.sessionId(), 0, Integer.MAX_VALUE));
				snapshot.addAll(history.delivered(previous.sessionId()));
				return createWithHistory(caller, new HandoffRequestDto(request.handoffId(), previous.conversationId(),
						request.previousSessionId(), request.displayedTurns()), snapshot);
			});
		}
		// Fetch the complete owned conversation before taking any DB/assignment lock.
		var saved = new ArrayList<HandoffRequestDto.Turn>();
		if (request.conversationId() != null) {
			for (var turn : conversations.getConversationMessageList(caller.id(), request.conversationId())) {
				saved.add(new HandoffRequestDto.Turn(turn.question(), turn.answer() == null ? "" : turn.answer()));
			}
		}
		List<HistoryMessageResponseDto> snapshot = new ArrayList<>();
		for (var turn : request.displayedTurns()) {
			int match = saved.indexOf(turn);
			if (match < 0) appendTurn(snapshot, turn, "SCREEN");
			else {
				for (int i = 0; i <= match; i++) appendTurn(snapshot, saved.get(i), "SAVED");
				saved.subList(0, match + 1).clear();
			}
		}
		for (var turn : saved) appendTurn(snapshot, turn, "SAVED");
		return lifecycle(() -> {
			snapshot.addAll(history.previousCounsel(caller.id(), request.conversationId()));
			return createWithHistory(caller, request, snapshot);
		});
	}
	private Session createWithHistory(Caller caller, HandoffRequestDto request, List<HistoryMessageResponseDto> snapshot) {
		var existing = existingHandoff(caller, request);
		if (existing != null) return existing;
		UUID conversationId = conversations.getOrCreateMemberConversation(caller.id(), request.conversationId()).getPublicId();
		List<HistoryMessageResponseDto> numbered = new ArrayList<>();
		for (var item : history.reuseScreenOrigins(conversationId, snapshot)) {
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
	private void appendTurn(List<HistoryMessageResponseDto> target, HandoffRequestDto.Turn turn, String source) {
		target.add(new HistoryMessageResponseDto(0, "USER", turn.question(), source));
		if (!turn.answer().isEmpty()) target.add(new HistoryMessageResponseDto(0, "AI", turn.answer(), source));
	}
	public Session getConversationSession(Caller caller, UUID conversationId) {
		caller.require("USER");
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
		if (!caller.role().equals("USER")) caller.require("COUNSELOR");
		return repository.current(caller.id(), caller.role().equals("COUNSELOR"));
	}
	public Session getSession(Caller caller, UUID id) {
		var session = repository.get(id, false);
		session.requireParticipant(caller);
		return session;
	}
	public String updateAvailability(Caller caller, String status) {
		caller.require("COUNSELOR");
		if (!status.equals("AVAILABLE") && !status.equals("OFFLINE")) throw new IllegalArgumentException();
		return lifecycle(() -> {
			repository.presence(caller.id(), status);
			repository.maintain(config.heartbeatSeconds(), config.reconnectSeconds());
			repository.assign(config.heartbeatSeconds());
			return repository.presenceStatus(caller.id());
		});
	}
	public String updateHeartbeat(Caller caller) {
		caller.require("COUNSELOR");
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
		} catch (org.springframework.dao.DataAccessException ignored) {
			// No SQL or message content in logs. Health endpoint reports DB outage.
		}
	}
}
