package com.ubot.counsel.safety.service;

import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.safety.client.EmbeddingClient;
import com.ubot.counsel.safety.model.GuardDecision;
import com.ubot.counsel.safety.repository.SafetyRepository;
import com.ubot.counsel.safety.rule.DirectInsultRules;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class SafetyGuardService {
	private final EmbeddingClient embedding;
	private final SafetyRepository repository;
	private final CounselProperties.Guard config;
	public SafetyGuardService(EmbeddingClient embedding, SafetyRepository repository, CounselProperties properties) {
		this.embedding = embedding;
		this.repository = repository; this.config = properties.guard();
	}
	@EventListener(ApplicationReadyEvent.class)
	public void recordConfiguration() { repository.recordConfiguration(); }
	public GuardDecision checkMessage(String content) {
		String normalized = DirectInsultRules.normalize(content);
		if (DirectInsultRules.explicit(normalized)) return new GuardDecision("BLOCKED", null, null, "INSULT", null);
		try {
			var matches = repository.findTopMatches(embedding.embed(normalized));
			if (matches.isEmpty()) return GuardDecision.failed("PROFILE_NOT_READY");
			var top = matches.getFirst();
			if (!Double.isFinite(top.score())) return GuardDecision.failed("INVALID_SIMILARITY");
			boolean block = top.score() >= config.threshold() && DirectInsultRules.semanticEligible(normalized);
			return new GuardDecision(block ? "BLOCKED" : "ALLOWED", top.id(), top.score(), top.category(), null);
		} catch (EmbeddingClient.Failure e) {
			return GuardDecision.failed(e.code());
		}
		catch (DataAccessException e) {
			return GuardDecision.failed("GUARD_DB");
		}
	}
}
