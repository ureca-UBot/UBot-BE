package com.ubot.counsel.message.service;

import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.counsel.message.dto.response.MessageReceiptResponseDto;
import com.ubot.counsel.message.dto.response.MessageResponseDto;
import com.ubot.counsel.message.entity.Message;
import com.ubot.counsel.message.repository.MessageRepository;
import com.ubot.counsel.safety.model.GuardDecision;
import com.ubot.counsel.safety.repository.SafetyRepository;
import com.ubot.counsel.safety.service.SafetyGuardService;
import com.ubot.counsel.session.repository.SessionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MessageService {
	private record Accepted(Message message, boolean fresh) {}
	private final MessageRepository messages;
	private final SessionRepository sessions;
	private final SafetyGuardService guard;
	private final SafetyRepository safety;
	private final TransactionTemplate tx;
	private final CounselProperties config;
	private final MessageRateLimitService rateLimit;
	public MessageService(MessageRepository messages, SessionRepository sessions, SafetyGuardService guard,
			SafetyRepository safety, TransactionTemplate tx, CounselProperties config, MessageRateLimitService rateLimit) {
		this.messages = messages;
		this.sessions = sessions; this.guard = guard;
		this.safety = safety;
		this.tx = tx; this.config = config; this.rateLimit = rateLimit;
	}
	public MessageReceiptResponseDto sendMessage(Caller sender, UUID id, UUID clientId, String content) {
		Accepted accepted = tx.execute(ignored -> {
			var session = sessions.get(id, true);
			session.requireParticipant(sender);
			var prior = messages.existing(id, sender.id(), clientId);
			if (prior != null) {
				if (!prior.content().equals(content)) throw CounselException.conflict("같은 전송 ID로 내용을 변경할 수 없습니다.");
				return new Accepted(prior, false);
			}
			session.requireActive();
			rateLimit.validateRequestRate(sender.id());
			return new Accepted(messages.insert(session, sender, clientId, content), true);
		});
		if (!accepted.fresh()) return accepted.message().receipt();
		// PENDING is committed. No transaction, connection, or session lock is held across HTTP.
		GuardDecision decision = guard.checkMessage(content);
		return complete(accepted.message(), decision);
	}
	private MessageReceiptResponseDto complete(Message original, GuardDecision verdict) {
		return tx.execute(ignored -> {
			var session = sessions.get(original.sessionId(), true);
			var message = messages.get(original.messageId());
			if (!message.status().equals("PENDING")) return message.receipt();
			GuardDecision finalDecision = verdict;
			if (!session.status().equals("ACTIVE") || session.assignmentVersion() != message.assignmentVersion()) {
				finalDecision = GuardDecision.failed("SESSION_CHANGED");
			}
			Long sequence = finalDecision.status().equals("ALLOWED") ? sessions.nextSequence(session.sessionId()) : null;
			messages.decide(message.messageId(), finalDecision.status(), sequence);
			safety.event(message.messageId(), finalDecision);
			if (sequence != null) sessions.touchConversation(session.sessionId());
			return messages.get(message.messageId()).receipt();
		});
	}
	public List<MessageResponseDto> getMessages(Caller caller, UUID id, long after, int limit) {
		if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException();
		return tx.execute(ignored -> {
			// Serialize this short authorization/read with reassignment so an old counselor cannot race it.
			var session = sessions.get(id, true);
			session.requireParticipant(caller);
			return messages.delivered(id, after, limit);
		});
	}
	public void recoverPending() {
		if (!config.enabled()) return;
		try {
			for (var pending : messages.stale(config.pendingTimeoutSeconds())) {
				complete(pending, GuardDecision.failed("PENDING_EXPIRED"));
			}
		} catch (org.springframework.dao.DataAccessException ignored) { }
	}
}
