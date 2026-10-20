package com.ubot.counsel.common.config;

import com.ubot.counsel.message.service.MessageService;
import com.ubot.counsel.session.service.SessionService;
import com.ubot.global.config.AppRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(AppRole.RUNS_SCHEDULERS)
public class CounselMaintenanceScheduler {
	private final SessionService sessions;
	private final MessageService messages;
	public CounselMaintenanceScheduler(SessionService sessions, MessageService messages) {
		this.sessions = sessions; this.messages = messages;
	}
	@Scheduled(fixedDelayString = "${counsel.maintenance-delay-ms:5000}")
	public void maintain() {
		sessions.maintainSessions();
		messages.recoverPending();
	}
}
