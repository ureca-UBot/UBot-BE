package com.ubot.guardreview.service;

import static com.ubot.guardreview.exception.ReviewErrorCode.CONFLICT;
import static com.ubot.guardreview.exception.ReviewErrorCode.INVALID;
import static com.ubot.guardreview.exception.ReviewErrorCode.TEST_REQUIRED;
import static com.ubot.guardreview.exception.ReviewErrorCode.WARNING_ONLY;
import static com.ubot.guardreview.model.ReviewModels.Kind;
import static com.ubot.guardreview.model.ReviewModels.Stage;
import static com.ubot.guardreview.model.ReviewModels.AnalysisMode;
import static com.ubot.guardreview.model.ReviewModels.RETENTION;
import static com.ubot.guardreview.model.ReviewModels.Settings;
import static com.ubot.guardreview.model.ReviewModels.Source;
import static com.ubot.guardreview.model.ReviewModels.Suggestion;
import static com.ubot.guardreview.model.ReviewModels.Vector;
import static com.ubot.guardreview.model.ReviewModels.Candidate;
import static com.ubot.guardreview.model.ReviewModels.Run;
import static com.ubot.guardreview.model.ReviewModels.Validation;
import static com.ubot.guardreview.model.ReviewModels.Approval;

import com.ubot.forbiddenword.dto.request.ForbiddenWordCreateRequestDto;
import com.ubot.forbiddenword.dto.request.ForbiddenWordStatusUpdateRequestDto;
import com.ubot.forbiddenword.enums.ForbiddenWordStatus;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import com.ubot.forbiddenword.service.ForbiddenWordService;
import com.ubot.guardreview.exception.ReviewException;
import com.ubot.guardreview.dto.response.ReviewRunResponseDto;
import com.ubot.guardreview.model.ReviewModels.CaseResult;
import com.ubot.guardreview.model.ReviewCheckModels.Origin;
import com.ubot.guardreview.model.ReviewCheckModels.Outcome;
import com.ubot.guardreview.model.ReviewModels.AnalysisSource;
import org.springframework.transaction.annotation.Isolation;
import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.common.PageResponseDto;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class ReviewService {
	private final ReviewRepository repository;
	private final ReviewValidationService validator;
	private final ForbiddenWordService wordService;
	private final ForbiddenWordRepository words;
	private final ReviewCatalogService catalog;
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Set<String> CATEGORIES = Set.of("ABUSE", "THREAT", "HATE", "FRAUD", "UNAUTHORIZED_ACCESS",
			"EXPLOITATION", "SELF_HARM", "MANIPULATION");
	private static final Set<String> FACTUAL_CATEGORIES = Set.of("FALSE_EXECUTION", "POLICY_CONTRADICTION",
			"UNSUPPORTED_CLAIM", "WRONG_SERVICE", "CONDITION_DISTORTION");
	public record TestRequest(List<Approval> selection, List<Candidate> candidates, Set<String> words,
			String baseVersion, String datasetVersion) {
	}

	@Transactional(readOnly = true)
	public java.util.Map<String, Object> getOverview() {
		return java.util.Map.of("settings", repository.settings(false), "runs",
				repository.runs().stream().map(ReviewRunResponseDto::from).toList(), "history", repository.history(),
				"retention", RETENTION, "capabilities", java.util.Map.of("checkMultiSelect", true, "checkSelectionAnalysis", true));
	}
	@Transactional(readOnly = true)
	public ReviewRunResponseDto getRun(long id) {
		return ReviewRunResponseDto.from(repository.runSummary(id));
	}
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponseDto<CaseResult> getValidationCasePage(long id, int page, int size) {
		if (page < 0 || !Set.of(10, 20, 50).contains(size))
			throw new ReviewException(INVALID);
		Run summary = repository.runSummary(id);
		if (!"VALIDATION".equals(summary.kind()))
			throw new ReviewException(INVALID);
		if (!summary.detailsRetained())
			throw new ReviewException(TEST_REQUIRED);
		if (!"COMPLETED".equals(summary.status()))
			throw new ReviewException(CONFLICT);
		// 상태·건수·페이지 본문을 같은 DB 스냅샷에서 읽어 보관기간 정리와의 조회 경합을 막습니다.
		return repository.getValidationCasePage(id, page, size);
	}
	@Transactional(readOnly = true)
	public PageResponseDto<Candidate> getCandidatePage(Kind kind, int page, int size) {
		if (kind == null || page < 0 || !Set.of(10, 20, 50).contains(size))
			throw new ReviewException(INVALID);
		var result = repository.getCandidatePage(kind.name(), page, size);
		return PageResponseDto.of(catalog.display(result.content()), page, size, result.totalElements());
	}
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponseDto<Candidate> getCandidatePage(Kind kind, int page, int size,
			Origin origin, Outcome outcome) {
		if (kind == null || page < 0 || !Set.of(10, 20, 50).contains(size)) throw new ReviewException(INVALID);
		var result = repository.getCandidatePage(kind.name(), page, size, origin, outcome);
		return PageResponseDto.of(catalog.display(result.content()), page, size, result.totalElements());
	}
	@Transactional
	public Settings updateSettings(boolean enabled, int minutes, double threshold, long revision, long admin) {
		return updateSettings(enabled, minutes, threshold, revision, admin, null);
	}
	@Transactional
	public Settings updateSettings(boolean enabled, int minutes, double threshold, long revision, long admin,
			AnalysisSource source) {
		if (minutes < 5 || minutes > 43200 || !Double.isFinite(threshold) || threshold < 0.5 || threshold > 1)
			throw new ReviewException(INVALID);
		return repository.saveSettings(enabled, minutes, threshold, revision, admin, source);
	}
	@Transactional
	public long startAnalysis(Long admin) {
		Settings settings = repository.settings(false);
		return enqueueAnalysis(admin, AnalysisMode.FULL,
				settings == null ? AnalysisSource.GENERATED_ANSWER : settings.analysisSource());
	}
	/** 현재 화면의 출처와 선택한 ID를 동결하며, 자동 차단 분석의 범위를 넓히지 않습니다. */
	@Transactional
	public long startReassessment(Origin origin, List<Long> checkIds, Long admin) {
		if (origin == null || !Set.of(Origin.INPUT_LLM, Origin.OUTPUT_LLM).contains(origin)
				|| checkIds == null || checkIds.isEmpty() || checkIds.size() > 50
				|| checkIds.stream().anyMatch(id -> id == null || id < 1)
				|| checkIds.stream().distinct().count() != checkIds.size()) throw new ReviewException(INVALID);
		var ids = checkIds.stream().sorted().toList();
		repository.analysisEnqueueLock();
		if (repository.analysisExists()) throw new ReviewException(CONFLICT);
		long run = repository.createRun("ANALYSIS", JSON.writeValueAsString(java.util.Map.of(
				"analysisMode", AnalysisMode.FULL, "scope", java.util.Map.of(
				"origin", origin, "outcome", Outcome.REVIEW, "checkIds", ids))), admin);
		if (repository.snapshotReviewSources(run, origin, ids) != ids.size()) throw new ReviewException(INVALID);
		return run;
	}
	/** 선택한 실제 검사만 동결합니다. 보류는 재검토하고 다른 판정은 기존 위험 표현 분석을 사용합니다. */
	@Transactional
	public long startCheckAnalysis(List<Long> checkIds, Long admin) {
		if (checkIds == null || checkIds.isEmpty()
				|| checkIds.stream().anyMatch(id -> id == null || id < 1)
				|| checkIds.stream().distinct().count() != checkIds.size()) throw new ReviewException(INVALID);
		var ids = checkIds.stream().sorted().toList();
		repository.analysisEnqueueLock();
		if (repository.analysisExists()) throw new ReviewException(CONFLICT);
		long run = repository.createRun("ANALYSIS", JSON.writeValueAsString(java.util.Map.of(
				"analysisMode", AnalysisMode.FULL, "scope", java.util.Map.of("checkIds", ids, "selection", true))), admin);
		if (repository.snapshotSelectedChecks(run, ids) != ids.size()) throw new ReviewException(INVALID);
		return run;
	}
	private long enqueueAnalysis(Long admin, AnalysisMode mode, AnalysisSource source) {
		repository.analysisEnqueueLock();
		if (repository.analysisExists())
			throw new ReviewException(CONFLICT);
		long id = repository.createRun("ANALYSIS", JSON.writeValueAsString(java.util.Map.of(
				"analysisMode", mode, "analysisSource", source)),
				admin);
		repository.snapshotSources(id, mode, source);
		return id;
	}
	@Transactional
	public void scheduleDue() {
		repository.analysisEnqueueLock();
		Settings s = repository.settings(true);
		if (!s.enabled() || s.nextRunAt() == null || s.nextRunAt().isAfter(LocalDateTime.now())
				|| repository.analysisExists())
			return;
		enqueueAnalysis(null, AnalysisMode.INCREMENTAL, s.analysisSource());
		repository.advanceSchedule();
	}
	@Transactional
	public void resumeAnalysis(long id) {
		repository.analysisEnqueueLock();
		if (repository.analysisExists())
			throw new ReviewException(CONFLICT);
		repository.resume(id);
	}
	@Transactional
	public Candidate updateCandidate(long id, long revision, String expression, String reason, long admin) {
		repository.catalogLock();
		Candidate c = repository.candidate(id, true);
		requireRevision(c, revision);
		if (c.kind() != Kind.WORD)
			throw new ReviewException(WARNING_ONLY);
		validateText(expression, 100);
		ReviewWordPolicy.requireEligible(expression);
		validateText(reason, 2000);
		Long linkedWord = catalog.linkForEdit(c, expression.strip());
		try {
			repository.edit(id, expression.strip(), reason.strip(), normalizedHash(expression), linkedWord);
		} catch (DuplicateKeyException e) {
			throw new ReviewException(CONFLICT);
		}
		Candidate changed = repository.candidate(id, false);
		repository.audit(changed, "EDIT", admin);
		return changed;
	}
	@Transactional
	public long startValidation(List<Approval> selection, long admin) {
		repository.catalogLock();
		List<Approval> ordered = ordered(selection);
		List<Candidate> candidates = ordered.stream().map(a -> {
			Candidate c = repository.candidate(a.candidateId(), true);
			requireRevision(c, a.revision());
			catalog.requireSelectable(c);
			return c;
		}).toList();
		Set<String> active = validator.currentWords();
		TestRequest request = new TestRequest(ordered, candidates, active, validator.baseVersion(active),
				validator.datasetVersion());
		long id = repository.createRun("VALIDATION", JSON.writeValueAsString(request), admin);
		repository.total(id, validator.dataset().size());
		return id;
	}
	public TestRequest getTestRequest(long runId) {
		return JSON.readValue(repository.request(runId), TestRequest.class);
	}
	@Transactional
	public void approveCandidates(long runId, List<Approval> selection, long admin) {
		repository.catalogLock();
		Run run = repository.run(runId);
		if (!"VALIDATION".equals(run.kind()) || !"COMPLETED".equals(run.status()) || !run.detailsRetained())
			throw new ReviewException(TEST_REQUIRED);
		TestRequest request = getTestRequest(runId);
		Validation result = JSON.readValue(run.result(), Validation.class);
		if (!request.selection().equals(ordered(selection)) || !result.passed())
			throw new ReviewException(TEST_REQUIRED);
		if (!validator.baseVersion().equals(request.baseVersion())
				|| !validator.datasetVersion().equals(request.datasetVersion())
				|| !result.baseVersion().equals(request.baseVersion())
				|| !result.datasetVersion().equals(request.datasetVersion()))
			throw new ReviewException(CONFLICT);
		List<Candidate> candidates = request.selection().stream().map(a -> {
			Candidate c = repository.candidate(a.candidateId(), true);
			requireRevision(c, a.revision());
			catalog.requireSelectable(c);
			return c;
		}).toList();
		// 검사한 묶음 전체를 한 트랜잭션으로 적용합니다. 중간 후보만 따로 적용하지 않습니다.
		for (Candidate c : candidates) {
			Long wordId = c.wordId();
			var previous = wordId == null
					? java.util.Optional.<com.ubot.forbiddenword.entity.ForbiddenWord>empty()
					: words.findById(wordId);
			if (previous.isPresent() && previous.get().getWord().equals(c.expression())
					&& previous.get().getStatus() == ForbiddenWordStatus.INACTIVE) {
				wordService.updateForbiddenWordStatus(wordId,
						new ForbiddenWordStatusUpdateRequestDto(ForbiddenWordStatus.ACTIVE));
			} else
				wordId = wordService.createForbiddenWord(new ForbiddenWordCreateRequestDto(c.expression())).id();
			repository.status(c.id(), "APPLIED", wordId);
			repository.audit(repository.candidate(c.id(), false), "APPLY", admin);
		}
	}
	@Transactional
	public void updateCandidateDecision(long id, long revision, String action, long admin) {
		repository.catalogLock();
		Candidate c = repository.candidate(id, true);
		requireRevision(c, revision);
		if ("DISABLE".equals(action)) {
			catalog.requireOwnedActive(c);
			wordService.updateForbiddenWordStatus(c.wordId(),
					new ForbiddenWordStatusUpdateRequestDto(ForbiddenWordStatus.INACTIVE));
			repository.status(id, "DISABLED", c.wordId());
		} else if ("REVIEWED".equals(action) && c.kind() == Kind.WARNING)
			repository.status(id, "REVIEWED", null);
		else if ("REJECT".equals(action) && !"APPLIED".equals(c.status()))
			repository.status(id, "REJECTED", null);
		else
			throw new ReviewException(INVALID);
		repository.audit(c, action, admin);
	}
	public static void validateSuggestion(Source source, Suggestion s) {
		if (s == null || s.kind() == null || s.stage() == null || s.stage() == Stage.COUNSEL)
			throw new ReviewException(INVALID);
		boolean factual = FACTUAL_CATEGORIES.contains(s.category());
		if (factual) {
			if (s.kind() != Kind.WARNING || s.stage() != Stage.OUTPUT || !"FACTUAL_ERROR".equals(s.intent())
					|| source.origin().startsWith("INPUT_") || source.context() == null
					|| JSON.readTree(source.context()).path("snapshot_unavailable").asBoolean(false))
				throw new ReviewException(INVALID);
		} else if (!CATEGORIES.contains(s.category()) || !Set.of("HARMFUL_REQUEST", "HARMFUL_OUTPUT").contains(s.intent()))
			throw new ReviewException(INVALID);
		if (source.origin().startsWith("INPUT_") && s.stage() != Stage.INPUT
				|| source.origin().startsWith("OUTPUT_") && s.stage() != Stage.OUTPUT)
			throw new ReviewException(INVALID);
		validateText(s.expression(), s.kind() == Kind.WORD ? 100 : 2000);
		validateText(s.reason(), 2000);
		String target = s.stage() == Stage.INPUT ? source.question() : source.answer();
		boolean quoted = target != null && target.contains(s.expression());
		if (!quoted && s.stage() == Stage.INPUT && source.context() != null) {
			for (var turn : JSON.readTree(source.context()).path("history"))
				quoted |= turn.path("question").asString("").contains(s.expression());
		}
		if (!quoted)
			throw new ReviewException(INVALID);
	}
	public static boolean usable(Vector vector) {
		if (vector == null || vector.profile() == null || vector.profile().isBlank() || vector.values() == null
				|| vector.values().length != 1024)
			return false;
		double norm = 0;
		for (float f : vector.values()) {
			if (!Float.isFinite(f))
				return false;
			norm += f * f;
		}
		return norm > 0;
	}
	public static String normalizedHash(String expression) {
		return ReviewValidationService.hash(com.ubot.counsel.safety.util.SafetyTextNormalizer.normalize(expression));
	}
	private static List<Approval> ordered(List<Approval> selection) {
		if (selection == null || selection.isEmpty() || selection.size() > 50
				|| selection.stream().anyMatch(a -> a == null || a.candidateId() < 1 || a.revision() < 1)
				|| selection.stream().map(Approval::candidateId).distinct().count() != selection.size())
			throw new ReviewException(INVALID);
		return selection.stream().sorted(java.util.Comparator.comparingLong(Approval::candidateId)).toList();
	}
	private static void requireRevision(Candidate c, long revision) {
		if (c.revision() != revision)
			throw new ReviewException(CONFLICT);
	}
	private static void validateText(String text, int max) {
		if (text == null || text.isBlank() || text.length() > max || text.codePoints()
				.allMatch(c -> Character.isWhitespace(c) || Character.getType(c) == Character.FORMAT))
			throw new ReviewException(INVALID);
	}
}
