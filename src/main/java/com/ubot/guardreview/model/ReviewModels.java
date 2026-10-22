package com.ubot.guardreview.model;

import java.time.LocalDateTime;
import java.util.List;

/** 후보는 운영 금칙어와 분리합니다. 경고 문장은 승인으로 활성화할 수 없습니다. */
public final class ReviewModels {

	private ReviewModels() {
	}

	public enum Kind {
		WORD,
		WARNING
	}
	public enum Stage {
		INPUT,
		OUTPUT,
		COUNSEL
	}
	public enum AnalysisMode {
		INCREMENTAL,
		FULL
	}
	public record RetentionPolicy(int detailDays, int summaryDays, int historyDays) {
	}
	public static final RetentionPolicy RETENTION = new RetentionPolicy(30, 90, 365);
	public record Settings(boolean enabled, int intervalMinutes, double groupingThreshold, long revision,
			LocalDateTime nextRunAt) {
	}
	public record Source(long id, String sourceKey, String question, String answer) {
	}
	public record Suggestion(Kind kind, String category, String expression, String reason, Stage stage, String intent) {
	}
	public record Vector(String profile, float[] values) {
	}
	public record Candidate(long id, Kind kind, String category, String expression, String reason, String intent,
			long revision, String status, Long wordId, String profile, float[] vector, long occurrences,
			long unreadOccurrences) {
	}
	public record Run(long id, String kind, String status, int processed, int total, LocalDateTime cutoff,
			String result, LocalDateTime createdAt, String analysisMode, boolean detailsRetained) {
		public Run(long id, String kind, String status, int processed, int total, LocalDateTime cutoff, String result,
				LocalDateTime createdAt) {
			this(id, kind, status, processed, total, cutoff, result, createdAt, "ANALYSIS".equals(kind) ? "FULL" : null,
					true);
		}
	}
	public record GoldenCase(String id, String category, Stage stage, String text, List<String> preserve,
			List<String> remove) {
	}
	public record CaseResult(String id, String category, Stage stage, String before, String after,
			boolean falsePositive, boolean regression, boolean baselineFailure, boolean newDetection) {
	}
	public record Validation(String baseVersion, String datasetVersion, List<CaseResult> cases, int falsePositives,
			int regressions, int baselineFailures, int newDetections) {
		public boolean passed() {
			return falsePositives == 0 && regressions == 0 && !cases.isEmpty();
		}
	}
	public record ValidationTicket(long runId, String status, Validation result) {
	}
	public record Approval(long candidateId, long revision) {
	}
	public record AlertSummary(long candidateId, int newCount, long totalCount) {
	}
	public record History(long id, long candidateId, long revision, String action, Long adminId, String expression,
			LocalDateTime createdAt) {
	}
}
