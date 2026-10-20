package com.ubot.counsel.admin.service;

import com.ubot.counsel.admin.dto.request.SafetySentenceCreateRequestDto;
import com.ubot.counsel.admin.dto.request.SafetySentenceUpdateRequestDto;
import com.ubot.counsel.admin.dto.response.SafetySentenceResponseDto;
import com.ubot.counsel.admin.dto.response.SafetySentencesResponseDto;
import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.common.exception.CounselErrorCode;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.counsel.message.service.MessageRateLimitService;
import com.ubot.counsel.safety.client.EmbeddingClient;
import com.ubot.counsel.safety.entity.SafetySentence;
import com.ubot.counsel.safety.repository.SafetySentenceRepository;
import com.ubot.counsel.safety.rule.DirectInsultRules;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminSafetySentenceService {
	private final SafetySentenceRepository repository;
	private final EmbeddingClient embedding;
	private final TransactionTemplate tx;
	private final MessageRateLimitService rateLimit;
	public AdminSafetySentenceService(SafetySentenceRepository repository, EmbeddingClient embedding,
			TransactionTemplate tx, MessageRateLimitService rateLimit) {
		this.repository = repository;
		this.embedding = embedding;
		this.tx = tx;
		this.rateLimit = rateLimit;
	}
	public SafetySentencesResponseDto getSentences(Caller caller, String keyword, int page) {
		caller.require("ADMIN");
		if (keyword.length() > 100 || page < 0 || page > 10000) throw new IllegalArgumentException();
		var rows = repository.list(keyword, page);
		return new SafetySentencesResponseDto(rows.stream().limit(50).map(SafetySentenceResponseDto::from).toList(),
				page, rows.size() > 50);
	}
	public SafetySentenceResponseDto getSentence(Caller caller, long id) {
		caller.require("ADMIN");
		return SafetySentenceResponseDto.from(get(id, false));
	}
	public SafetySentenceResponseDto createSentence(Caller caller, SafetySentenceCreateRequestDto request) {
		caller.require("ADMIN");
		String hash = contentHash(request.content());
		try {
			return tx.execute(ignored -> {
				requireProfile();
				long id = repository.insert(request.content(), hash, request.severity());
				repository.audit(id, caller.id(), "CREATE");
				return SafetySentenceResponseDto.from(get(id, false));
			});
		} catch (DuplicateKeyException duplicate) {
			throw new CounselException(CounselErrorCode.SAFETY_SENTENCE_DUPLICATE);
		}
	}
	public SafetySentenceResponseDto updateSentence(Caller caller, long id, SafetySentenceUpdateRequestDto request) {
		caller.require("ADMIN");
		String hash = contentHash(request.content());
		try {
			return tx.execute(ignored -> {
				var sentence = get(id, true);
				requireRevision(sentence, request.revision());
				if (sentence.content().equals(request.content()) && sentence.severity() == request.severity()
						&& sentence.enabled() == request.enabled()) return SafetySentenceResponseDto.from(sentence);
				repository.deactivateVector(sentence);
				repository.update(id, request.content(), hash, request.severity(), request.enabled());
				repository.audit(id, caller.id(), "UPDATE");
				return SafetySentenceResponseDto.from(get(id, false));
			});
		} catch (DuplicateKeyException duplicate) {
			throw new CounselException(CounselErrorCode.SAFETY_SENTENCE_DUPLICATE);
		}
	}
	public void deleteSentence(Caller caller, long id, long revision) {
		caller.require("ADMIN");
		tx.executeWithoutResult(ignored -> {
			var sentence = get(id, true);
			requireRevision(sentence, revision);
			repository.deactivateVector(sentence);
			repository.delete(id);
			repository.audit(id, caller.id(), "DELETE");
		});
	}
	public SafetySentenceResponseDto indexSentence(Caller caller, long id, long revision) {
		caller.require("ADMIN");
		UUID token = UUID.randomUUID();
		var original = tx.execute(ignored -> {
			var sentence = get(id, true);
			requireRevision(sentence, revision);
			requireProfile();
			if (!sentence.enabled()) throw CounselException.conflict("활성화한 문장만 색인할 수 있습니다.");
			if (sentence.currentIndex()) return sentence;
			if (sentence.indexInProgress()) throw CounselException.conflict("이미 색인 중입니다. 잠시 후 상태를 확인해 주세요.");
			rateLimit.validateRequestRate(caller.id());
			repository.beginIndex(id, token);
			repository.audit(id, caller.id(), "INDEX_REQUESTED");
			return sentence;
		});
		if (original.currentIndex()) return SafetySentenceResponseDto.from(original);
		// Release the row lock and DB connection before waiting for the existing embedding worker.
		String vector;
		try {
			vector = embedding.embed(original.content());
		} catch (EmbeddingClient.Failure failure) {
			tx.executeWithoutResult(ignored -> {
				var current = get(id, true);
				requireIndexOwner(current, revision, token);
				repository.failIndex(id, failure.code());
				repository.audit(id, caller.id(), "INDEX_FAILED");
			});
			throw new CounselException(CounselErrorCode.SAFETY_INDEX_UNAVAILABLE);
		}
		return tx.execute(ignored -> {
			var current = get(id, true);
			requireIndexOwner(current, revision, token);
			requireProfile();
			repository.completeIndex(current, vector);
			repository.audit(id, caller.id(), "INDEXED");
			return SafetySentenceResponseDto.from(get(id, false));
		});
	}
	private SafetySentence get(long id, boolean lock) {
		if (id <= 0) throw new IllegalArgumentException();
		var sentence = repository.get(id, lock);
		if (sentence == null) throw new CounselException(CounselErrorCode.SAFETY_SENTENCE_NOT_FOUND);
		return sentence;
	}
	private void requireProfile() {
		if (!repository.profileReady()) throw new CounselException(CounselErrorCode.SAFETY_PROFILE_NOT_READY);
	}
	private void requireRevision(SafetySentence sentence, long revision) {
		if (revision < 1) throw new IllegalArgumentException();
		if (sentence.revision() != revision) throw CounselException.conflict("문장이 변경됐습니다. 최신 내용을 다시 조회해 주세요.");
	}
	private void requireIndexOwner(SafetySentence sentence, long revision, UUID token) {
		requireRevision(sentence, revision);
		if (!sentence.enabled() || !token.equals(sentence.indexToken())) {
			throw CounselException.conflict("색인 요청이 변경됐습니다. 최신 상태를 다시 조회해 주세요.");
		}
	}
	private String contentHash(String content) {
		String normalized = DirectInsultRules.normalize(content);
		if (normalized.isBlank() || normalized.length() > 2000) throw new IllegalArgumentException();
		return DirectInsultRules.contentHash(content);
	}
}
