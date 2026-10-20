package com.ubot.counsel.safety.service;

import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.safety.client.EmbeddingClient;
import com.ubot.counsel.safety.model.GuardDecision;
import com.ubot.counsel.safety.repository.SafetyRepository;
import com.ubot.forbiddenword.exception.ForbiddenWordErrorCode;
import com.ubot.forbiddenword.exception.ForbiddenWordException;
import com.ubot.forbiddenword.service.ForbiddenWordFilterService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class SafetyGuardService {
	private final ForbiddenWordFilterService forbiddenWordFilterService;
	private final EmbeddingClient embedding;
	private final SafetyRepository repository;
	private final CounselProperties.Guard config;

	public SafetyGuardService(ForbiddenWordFilterService forbiddenWordFilterService, EmbeddingClient embedding,
			SafetyRepository repository, CounselProperties properties) {
		this.forbiddenWordFilterService = forbiddenWordFilterService;
		this.embedding = embedding;
		this.repository = repository;
		this.config = properties.guard();
	}

	@EventListener(ApplicationReadyEvent.class)
	public void recordConfiguration() { repository.recordConfiguration(); }

	public GuardDecision checkMessage(String content) {
		try {
			// 기존 채팅과 동일한 원문·활성 금지어 정책을 사용하고 상담 판정·감사 계약은 유지한다.
			forbiddenWordFilterService.validateForbiddenWord(content);
		} catch (ForbiddenWordException e) {
			if (e.getErrorCode() != ForbiddenWordErrorCode.FORBIDDEN_WORD_DETECTED) throw e;
			return new GuardDecision("BLOCKED", null, null, "INSULT", null);
		}

		try {
			var matches = repository.findTopMatches(embedding.embed(content));
			if (matches.isEmpty()) return GuardDecision.failed("PROFILE_NOT_READY");
			var top = matches.getFirst();
			if (!Double.isFinite(top.score())) return GuardDecision.failed("INVALID_SIMILARITY");
			boolean block = top.score() >= config.threshold();
			return new GuardDecision(block ? "BLOCKED" : "ALLOWED", top.id(), top.score(), top.category(), null);
		} catch (EmbeddingClient.Failure e) {
			return GuardDecision.failed(e.code());
		} catch (DataAccessException e) {
			return GuardDecision.failed("GUARD_DB");
		}
	}
}
