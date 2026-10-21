package com.ubot.counsel.common.config;

import com.ubot.counsel.admin.service.AdminSafetySentenceService;
import com.ubot.counsel.message.service.MessageService;
import com.ubot.counsel.session.service.SessionService;
import com.ubot.global.config.AppRole;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(AppRole.RUNS_SCHEDULERS)
@RequiredArgsConstructor
public class CounselMaintenanceScheduler {
	private final SessionService sessions;
	private final MessageService messages;
	private final AdminSafetySentenceService safetySentences;
	private final CounselProperties config;
	@Scheduled(fixedDelayString = "${counsel.maintenance-delay-ms:5000}")
	public void maintain() {
		sessions.maintainSessions();
		messages.recoverPending();
	}
	@Scheduled(fixedDelayString = "${counsel.maintenance-delay-ms:5000}", scheduler = "counselIndexScheduler")
	public void indexSentences() {
		if (config.enabled()) safetySentences.indexPendingSentences();
	}
}
