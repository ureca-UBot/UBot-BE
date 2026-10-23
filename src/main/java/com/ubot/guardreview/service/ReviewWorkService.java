package com.ubot.guardreview.service;

import static com.ubot.guardreview.model.ReviewModels.Kind;
import static com.ubot.guardreview.model.ReviewModels.Source;
import static com.ubot.guardreview.model.ReviewModels.Suggestion;
import static com.ubot.guardreview.model.ReviewModels.Vector;

import com.ubot.guardreview.repository.ReviewRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 모델 응답이 돌아온 뒤 소유권을 다시 확인하여, lease를 잃은 작업의 쓰기를 막습니다. */
@Service
@RequiredArgsConstructor
public class ReviewWorkService {
	private final ReviewRepository repository;
	private final ReviewCatalogService catalog;
	public record Proposal(Suggestion suggestion, Vector vector) {
	}
	@Transactional
	public void finishSource(long runId, UUID token, Source source, List<Proposal> proposals, boolean success) {
		repository.analysisLock();
		repository.requireLease(runId, token);
		if (!repository.finishItem(source.id(), success))
			return;
		repository.clearAnalysisRetry(runId);
		for (Proposal proposal : proposals) {
			Suggestion s = proposal.suggestion();
			ReviewService.validateSuggestion(source, s);
			s = ReviewWordPolicy.forReview(s);
			Vector vector = ReviewService.usable(proposal.vector()) ? proposal.vector() : null;
			Long id = s.kind() == Kind.WARNING ? repository.existingWarning(source, s) : null;
			if (id == null)
				id = repository.exact(s, ReviewService.normalizedHash(s.expression()));
			if (id == null && s.kind() == Kind.WORD && catalog.isRegistered(s.expression()))
				continue;
			if (id == null && s.kind() == Kind.WARNING && vector != null) {
				var groups = repository.similar(s, vector, repository.settings(false).groupingThreshold());
				if (!groups.isEmpty())
					id = groups.getFirst();
			}
			if (id == null)
				id = repository.insertCandidate(s, ReviewService.normalizedHash(s.expression()), vector);
			repository.finding(id, runId, source, s);
		}
		repository.updateAnalysisProgress(runId);
	}
	@Transactional
	public void release(long id, UUID token) {
		repository.requireLease(id, token);
		repository.releaseAnalysis(id, token);
	}
	@Transactional
	public void failAnalysis(long id, UUID token) {
		repository.requireLease(id, token);
		repository.failAnalysis(id, token);
	}
	@Transactional
	public void retryAnalysis(long id, UUID token, Source source, String errorCode) {
		repository.analysisLock();
		repository.requireLease(id, token);
		repository.retryAnalysis(id, token, source, errorCode);
	}
	@Transactional
	public void finishValidation(long id, UUID token, String json, boolean success) {
		repository.requireLease(id, token);
		repository.completeRun(id, token, json, success);
	}
}
