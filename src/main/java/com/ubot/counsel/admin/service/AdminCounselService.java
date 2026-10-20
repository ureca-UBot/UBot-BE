package com.ubot.counsel.admin.service;

import com.ubot.counsel.admin.dto.response.SafetyEventsResponseDto;
import com.ubot.counsel.admin.dto.response.SafetySummaryResponseDto;
import com.ubot.counsel.admin.repository.AdminCounselRepository;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.common.exception.CounselErrorCode;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.counsel.message.service.MessageRateLimitService;
import com.ubot.counsel.safety.model.GuardDecision;
import com.ubot.counsel.safety.service.SafetyGuardService;
import com.ubot.counsel.session.repository.SessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminCounselService {
	private final AdminCounselRepository repository;
	private final SessionRepository sessions;
	private final CounselProperties config;
	private final TransactionTemplate tx;
	private final SafetyGuardService guard;
	private final MessageRateLimitService rateLimit;
	public AdminCounselService(AdminCounselRepository repository, SessionRepository sessions, CounselProperties config,
			TransactionTemplate tx, SafetyGuardService guard, MessageRateLimitService rateLimit) {
		this.repository = repository;
		this.sessions = sessions; this.config = config;
		this.tx = tx;
		this.guard = guard; this.rateLimit = rateLimit;
	}
	private void range(Instant from, Instant to) {
		if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) throw new IllegalArgumentException();
	}
	public SafetyEventsResponseDto getSafetyEvents(Caller caller, String decision, Instant from, Instant to, int page) {
		caller.require("ADMIN"); range(from, to);
		if (!Set.of("", "ALLOWED", "BLOCKED", "CHECK_FAILED").contains(decision) || page < 0 || page > 10000) throw new IllegalArgumentException();
		var rows = repository.events(decision, from, to, page);
		return new SafetyEventsResponseDto(rows.stream().limit(50).toList(), page, rows.size() > 50);
	}
	public SafetySummaryResponseDto getSafetySummary(Caller caller, Instant from, Instant to) {
		caller.require("ADMIN"); range(from, to);
		return repository.summary(from, to);
	}
	public void updateCounselorRole(Caller caller, long userId, boolean promote) {
		caller.require("ADMIN");
		if (userId <= 0 || userId == caller.id()) throw CounselException.forbidden();
		tx.executeWithoutResult(ignored -> {
			sessions.lockQueue();
			String role = repository.lockRole(userId);
			if (role == null) throw new CounselException(CounselErrorCode.USER_NOT_FOUND);
			String desired = promote ? "COUNSELOR" : "USER";
			if (desired.equals(role)) return;
			if (!role.equals(promote ? "USER" : "COUNSELOR")) throw CounselException.forbidden();
			if (repository.hasOpenSession(userId)) throw CounselException.conflict("진행 중인 상담을 먼저 종료해 주세요.");
			repository.changeRole(caller.id(), userId, role, desired);
		});
	}
	public GuardDecision evaluateMessage(Caller caller, String content) {
		caller.require("ADMIN");
		tx.executeWithoutResult(ignored -> rateLimit.validateRequestRate(caller.id()));
		// Held-out evaluation is never inserted into search vectors, messages, or operational events.
		return guard.checkMessage(content);
	}
}
