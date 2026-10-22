package com.ubot.guardreview.repository;

import static com.ubot.guardreview.exception.ReviewErrorCode.NOT_FOUND;
import static com.ubot.guardreview.exception.ReviewErrorCode.CONFLICT;
import static com.ubot.guardreview.model.ReviewModels.Kind;
import static com.ubot.guardreview.model.ReviewModels.AnalysisMode;
import static com.ubot.guardreview.model.ReviewModels.RETENTION;
import static com.ubot.guardreview.model.ReviewModels.Settings;
import static com.ubot.guardreview.model.ReviewModels.Source;
import static com.ubot.guardreview.model.ReviewModels.Suggestion;
import static com.ubot.guardreview.model.ReviewModels.Vector;
import static com.ubot.guardreview.model.ReviewModels.Candidate;
import static com.ubot.guardreview.model.ReviewModels.Run;
import static com.ubot.guardreview.model.ReviewModels.CaseResult;
import static com.ubot.guardreview.model.ReviewModels.AlertSummary;
import static com.ubot.guardreview.model.ReviewModels.History;

import com.pgvector.PGvector;
import com.ubot.forbiddenword.service.ForbiddenWordCatalogLock;
import com.ubot.guardreview.exception.ReviewException;
import com.ubot.common.PageResponseDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 외부 모델 호출은 이 저장소와 트랜잭션 밖에서 실행합니다. */
@Repository
@RequiredArgsConstructor
public class ReviewRepository {
	private final JdbcTemplate jdbc;
	@NonNull
	private final ForbiddenWordCatalogLock catalogLock;
	private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
			.build();
	private static final String DETAILS_RETAINED = "details_retained AND (status NOT IN ('COMPLETED','FAILED')"
			+ " OR COALESCE(finished_at,created_at) >= LOCALTIMESTAMP-make_interval(days => " + RETENTION.detailDays()
			+ "))";
	private static final String DETAILS_AVAILABLE = DETAILS_RETAINED + " AS details_available";
	private static final String VALIDATION_CASES = "CASE WHEN jsonb_typeof(result->'cases')='array' THEN result->'cases' ELSE '[]'::jsonb END";
	private static final String RUN_COLUMNS = "id,kind,analysis_mode,status,processed,total,cutoff,created_at,";
	private static final String RUN_SUMMARY = "SELECT " + RUN_COLUMNS + "((result-'cases')||"
			+ "CASE WHEN kind='VALIDATION' THEN jsonb_build_object('caseCount',jsonb_array_length(" + VALIDATION_CASES
			+ ")) ELSE '{}'::jsonb END)::text AS result," + DETAILS_AVAILABLE + " FROM guard_review_runs";

	private static final String CANDIDATE = """
			SELECT c.id,c.kind,c.category,c.expression,c.reason,c.intent,c.revision,c.status,c.word_id,c.embedding_profile,
			(SELECT count(*) FROM guard_review_findings f WHERE f.candidate_id=c.id) AS occurrences,
			(SELECT count(*) FROM guard_review_findings f WHERE f.candidate_id=c.id AND f.notified_at IS NULL) AS unread
			FROM guard_review_candidates c
			""";

	public void catalogLock() {
		catalogLock.acquire();
	}
	/** 후보·발견 기록 완료와 알림·보관기간 정리 사이의 짧은 쓰기를 직렬화합니다. */
	public void analysisLock() {
		jdbc.execute("SELECT pg_advisory_xact_lock(674102232)");
	}
	/** 수동·예약 분석 접수와 재개만 직렬화합니다. 스냅샷이 완료·알림 잠금을 점유하지 않습니다. */
	public void analysisEnqueueLock() {
		jdbc.execute("SELECT pg_advisory_xact_lock(674102233)");
	}
	public Settings settings(boolean lock) {
		return jdbc.queryForObject("SELECT * FROM guard_review_settings WHERE id=1" + (lock ? " FOR UPDATE" : ""),
				(r, n) -> new Settings(r.getBoolean("enabled"), r.getInt("interval_minutes"),
						r.getDouble("grouping_threshold"), r.getLong("revision"),
						r.getObject("next_run_at", LocalDateTime.class)));
	}
	public Settings saveSettings(boolean enabled, int minutes, double threshold, long revision, long adminId) {
		if (jdbc.update(
				"""
						UPDATE guard_review_settings SET updated_at=CURRENT_TIMESTAMP,enabled=?, interval_minutes=?, grouping_threshold=?, revision=revision+1,
						next_run_at=CASE WHEN ? THEN LOCALTIMESTAMP + make_interval(mins => ?) ELSE NULL END,
						updated_by=? WHERE id=1 AND revision=?
						""",
				enabled, minutes, threshold, enabled, minutes, adminId, revision) != 1)
			throw new ReviewException(CONFLICT);
		return settings(false);
	}
	public boolean analysisExists() {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS(SELECT 1 FROM guard_review_runs WHERE kind='ANALYSIS' AND status IN ('QUEUED','RUNNING'))",
				Boolean.class));
	}
	public void advanceSchedule() {
		jdbc.update(
				"UPDATE guard_review_settings SET updated_at=CURRENT_TIMESTAMP,next_run_at=LOCALTIMESTAMP + make_interval(mins => interval_minutes) WHERE id=1");
	}
	public long createRun(String kind, String request, Long adminId) {
		return jdbc.queryForObject("""
				INSERT INTO guard_review_runs(kind,analysis_mode,request,requested_by)
				VALUES (?,CASE WHEN ?='ANALYSIS' THEN ?::jsonb->>'analysisMode' END,?::jsonb,?) RETURNING id
				""", Long.class, kind, kind, request, request, adminId);
	}
	public void snapshotSources(long runId, AnalysisMode mode) {
		// 재시도는 마지막 시도 하나로 합칩니다. 별도 멱등키를 쓰는 의도 재검색 답변도 수집합니다.
		// 원문을 동결한 뒤 실제 분석한 내용의 지문을 기록합니다. 실패·분석 중인 항목은 성공 기준으로 쓰지 않습니다.
		jdbc.update(
				"""
						WITH latest AS (
						 SELECT a.attempt_id,a.question,'sha256:'||encode(sha256(convert_to(concat_ws(':',coalesce(CAST(a.conversation_id AS text),'none'),
						 coalesce(CAST(a.user_id AS text),'guest'),a.idempotency_key),'UTF8')),'hex') AS source_key,
						 row_number() OVER (PARTITION BY a.conversation_id,a.user_id,a.idempotency_key
						  ORDER BY a.attempt_count DESC,a.attempt_id DESC) AS source_rank
						 FROM answer_attempts_history a
						 WHERE a.created_at <= (SELECT cutoff FROM guard_review_runs WHERE id=?)
						), snapshots AS (
						 SELECT a.*,(SELECT llm_question FROM question_log WHERE attempt_id=a.attempt_id
						  AND created_at <= (SELECT cutoff FROM guard_review_runs WHERE id=?)
						  ORDER BY sub_question_index,id LIMIT 1) AS answer
						 FROM latest a WHERE source_rank=1
						), fingerprints AS (
						 SELECT s.*,encode(sha256(convert_to(CAST(jsonb_build_array(s.question,s.answer) AS text),'UTF8')),'hex') AS fingerprint
						 FROM snapshots s
						)
						INSERT INTO guard_review_items(run_id,source_id,source_key,source_fingerprint,question_snapshot,answer_snapshot)
						SELECT ?,s.attempt_id,s.source_key,s.fingerprint,s.question,s.answer FROM fingerprints s
						WHERE ?='FULL' OR (
						 SELECT source_fingerprint FROM guard_review_items previous
						 WHERE previous.source_key=s.source_key AND previous.status='COMPLETED'
						 ORDER BY previous.finished_at DESC,previous.id DESC LIMIT 1
						) IS DISTINCT FROM s.fingerprint
						ON CONFLICT(run_id,source_key) DO NOTHING
						""",
				runId, runId, runId, mode.name());
		jdbc.update(
				"UPDATE guard_review_runs SET updated_at=CURRENT_TIMESTAMP,total=(SELECT count(*) FROM guard_review_items WHERE run_id=?) WHERE id=?",
				runId, runId);
	}
	/** 목록과 상태 확인에는 사례 본문과 동결 요청을 읽어 오지 않습니다. */
	public List<Run> runs() {
		return jdbc.query(RUN_SUMMARY + " ORDER BY id DESC LIMIT 100", this::mapRun);
	}
	public Run runSummary(long id) {
		return jdbc.query(RUN_SUMMARY + " WHERE id=?", this::mapRun, id).stream().findFirst()
				.orElseThrow(() -> new ReviewException(NOT_FOUND));
	}
	/** 승인 판정만 저장된 전체 검사 결과를 읽습니다. 이 내부 모델을 API에 직접 반환하지 않습니다. */
	public Run run(long id) {
		return jdbc
				.query("SELECT " + RUN_COLUMNS + "result::text AS result," + DETAILS_AVAILABLE
						+ " FROM guard_review_runs WHERE id=?", this::mapRun, id)
				.stream().findFirst().orElseThrow(() -> new ReviewException(NOT_FOUND));
	}
	/** 본문은 요청한 인덱스만 꺼내며, Java에서 전체 cases 배열을 역직렬화하지 않습니다. */
	public PageResponseDto<CaseResult> getValidationCasePage(long id, int page, int size) {
		String available = " FROM guard_review_runs WHERE id=? AND kind='VALIDATION' AND status='COMPLETED' AND "
				+ DETAILS_RETAINED;
		Integer total = jdbc.queryForObject("SELECT jsonb_array_length(" + VALIDATION_CASES + ")" + available,
				Integer.class, id);
		long offset = (long) page * size;
		var content = jdbc.query("WITH validation AS MATERIALIZED (SELECT " + VALIDATION_CASES + " AS cases" + available
				+ ") SELECT (v.cases->n::int)::text FROM validation v"
				+ " CROSS JOIN LATERAL generate_series(?::bigint,LEAST(?::bigint,jsonb_array_length(v.cases)-1)) n"
				+ " ORDER BY n", (row, index) -> JSON.readValue(row.getString(1), CaseResult.class), id, offset,
				offset + size - 1);
		return PageResponseDto.of(content, page, size, total == null ? 0 : total);
	}
	private Run mapRun(ResultSet r, int n) throws SQLException {
		return new Run(r.getLong("id"), r.getString("kind"), r.getString("status"), r.getInt("processed"),
				r.getInt("total"), r.getObject("cutoff", LocalDateTime.class), r.getString("result"),
				r.getObject("created_at", LocalDateTime.class), r.getString("analysis_mode"),
				r.getBoolean("details_available"));
	}
	public String request(long id) {
		return jdbc.queryForObject("SELECT request::text FROM guard_review_runs WHERE id=?", String.class, id);
	}
	public void total(long id, int count) {
		jdbc.update("UPDATE guard_review_runs SET updated_at=CURRENT_TIMESTAMP,total=? WHERE id=?", count, id);
	}
	public Long claim(UUID token) {
		return jdbc
				.query("""
						UPDATE guard_review_runs SET updated_at=CURRENT_TIMESTAMP,status='RUNNING',lease_token=?,lease_until=LOCALTIMESTAMP+interval '5 minutes'
						WHERE id=(SELECT id FROM guard_review_runs WHERE status IN ('QUEUED','RUNNING')
						 AND (lease_until IS NULL OR lease_until < LOCALTIMESTAMP)
						 ORDER BY CASE WHEN kind='VALIDATION' THEN 0 ELSE 1 END,id LIMIT 1 FOR UPDATE SKIP LOCKED)
						RETURNING id
						""",
						(r, n) -> r.getLong(1), token)
				.stream().findFirst().orElse(null);
	}
	public void requireLease(long id, UUID token) {
		if (jdbc.query(
				"SELECT id FROM guard_review_runs WHERE id=? AND lease_token=? AND lease_until>LOCALTIMESTAMP FOR UPDATE",
				(r, n) -> r.getLong(1), id, token).isEmpty())
			throw new ReviewException(CONFLICT);
	}
	public Source nextSource(long runId) {
		return jdbc
				.query("""
						SELECT i.id,i.source_key,i.question_snapshot AS question,i.answer_snapshot AS answer FROM guard_review_items i
						WHERE i.run_id=? AND i.status='PENDING' ORDER BY i.id LIMIT 1
						""",
						(r, n) -> new Source(r.getLong("id"), r.getString("source_key"), r.getString("question"),
								r.getString("answer")),
						runId)
				.stream().findFirst().orElse(null);
	}
	public boolean finishItem(long itemId, boolean success) {
		return jdbc.update(
				"UPDATE guard_review_items SET updated_at=CURRENT_TIMESTAMP,status=?,error_code=?,finished_at=LOCALTIMESTAMP WHERE id=? AND status='PENDING'",
				success ? "COMPLETED" : "FAILED", success ? null : "ANALYSIS_FAILED", itemId) == 1;
	}
	public boolean renewLease(long id, UUID token) {
		return jdbc.update(
				"""
						UPDATE guard_review_runs SET lease_until=LOCALTIMESTAMP+interval '5 minutes',updated_at=CURRENT_TIMESTAMP
						WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>LOCALTIMESTAMP
						""",
				id, token) == 1;
	}
	public void releaseAnalysis(long id, UUID token) {
		finishAnalysis(id, token, false);
	}
	public void failAnalysis(long id, UUID token) {
		finishAnalysis(id, token, true);
	}
	private void finishAnalysis(long id, UUID token, boolean failed) {
		jdbc.update(
				"""
						WITH stats AS (
						 SELECT count(*) FILTER (WHERE status='COMPLETED')::int AS completed,
						 count(*) FILTER (WHERE status='FAILED')::int AS failed,
						 count(*) FILTER (WHERE status='PENDING')::int AS pending FROM guard_review_items WHERE run_id=?)
						UPDATE guard_review_runs r SET updated_at=CURRENT_TIMESTAMP,processed=s.completed+s.failed,
						 status=CASE WHEN ? THEN 'FAILED' WHEN s.pending>0 THEN 'RUNNING'
						 WHEN s.failed>0 THEN 'FAILED' ELSE 'COMPLETED' END,
						 result=(r.result-'retryAt')||jsonb_build_object('completed',s.completed,'failed',s.failed,'pending',s.pending)
						 ||CASE WHEN ? THEN '{"error":"REVIEW_FAILED"}'::jsonb ELSE '{}'::jsonb END,
						 finished_at=CASE WHEN ? OR s.pending=0 THEN LOCALTIMESTAMP ELSE NULL END,
						 lease_token=NULL,lease_until=NULL FROM stats s WHERE r.id=? AND r.kind='ANALYSIS' AND r.lease_token=?
						""",
				id, failed, failed, failed, id, token);
	}
	public void clearAnalysisRetry(long id) {
		jdbc.update("""
				UPDATE guard_review_runs SET result=result-'consecutiveFailures'-'retryAt'-'errorCode'-'lastSourceId'
				WHERE id=? AND kind='ANALYSIS'
				""", id);
	}
	/** 일시 장애는 같은 항목을 최대 세 번 시도하며, 대기 상태는 DB에 남겨 다른 Worker도 지킵니다. */
	public void retryAnalysis(long id, UUID token, Source source, String errorCode) {
		int failures = jdbc.queryForObject("""
				SELECT COALESCE((result->>'consecutiveFailures')::int,0)+1 FROM guard_review_runs WHERE id=?
				""", Integer.class, id);
		int delay = failures == 1 ? 10 : 30;
		jdbc.update("""
				UPDATE guard_review_runs SET result=result||jsonb_build_object('consecutiveFailures',?::int,
				 'lastSourceId',?::bigint,'errorCode',?::text),updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_token=?
				""", failures, source.id(), errorCode, id, token);
		if (failures >= 3) {
			jdbc.update("""
					UPDATE guard_review_items SET status='FAILED',error_code=?,finished_at=LOCALTIMESTAMP,
					updated_at=CURRENT_TIMESTAMP WHERE id=? AND run_id=? AND status='PENDING'
					""", errorCode, source.id(), id);
			failAnalysis(id, token);
			return;
		}
		jdbc.update("""
				UPDATE guard_review_runs SET lease_token=NULL,lease_until=LOCALTIMESTAMP+make_interval(secs=>?),
				 result=result||jsonb_build_object('retryAt',LOCALTIMESTAMP+make_interval(secs=>?)),
				 processed=(SELECT count(*) FROM guard_review_items WHERE run_id=? AND status<>'PENDING'),
				 updated_at=CURRENT_TIMESTAMP WHERE id=? AND kind='ANALYSIS' AND lease_token=?
				""", delay, delay, id, id, token);
	}
	public void completeRun(long id, UUID token, String result, boolean success) {
		jdbc.update(
				"UPDATE guard_review_runs SET updated_at=CURRENT_TIMESTAMP,status=?,result=?::jsonb,processed=CASE WHEN ? THEN total ELSE processed END,finished_at=LOCALTIMESTAMP,lease_token=NULL,lease_until=NULL WHERE id=? AND kind='VALIDATION' AND lease_token=?",
				success ? "COMPLETED" : "FAILED", result, success, id, token);
	}
	public void resume(long id) {
		if (jdbc.update(
				"UPDATE guard_review_runs SET updated_at=CURRENT_TIMESTAMP,status='QUEUED',finished_at=NULL,lease_token=NULL,lease_until=NULL,"
						+ " result='{}'::jsonb,processed=(SELECT count(*) FROM guard_review_items WHERE run_id=? AND status='COMPLETED')"
						+ " WHERE id=? AND kind='ANALYSIS' AND status='FAILED' AND details_retained"
						+ " AND COALESCE(finished_at,created_at)>=LOCALTIMESTAMP-make_interval(days => ?)",
				id, id, RETENTION.detailDays()) != 1)
			throw new ReviewException(CONFLICT);
		jdbc.update(
				"UPDATE guard_review_items SET updated_at=CURRENT_TIMESTAMP,status='PENDING',error_code=NULL,finished_at=NULL WHERE run_id=? AND status='FAILED'",
				id);
	}
	public PageResponseDto<Candidate> getCandidatePage(String kind, int page, int size) {
		Long total = jdbc.queryForObject("SELECT count(*) FROM guard_review_candidates WHERE kind=?", Long.class, kind);
		var content = jdbc.query(CANDIDATE + " WHERE c.kind=? ORDER BY c.id DESC LIMIT ? OFFSET ?", this::candidate,
				kind, size, (long) page * size);
		return PageResponseDto.of(content, page, size, total == null ? 0 : total);
	}
	public Candidate candidate(long id, boolean lock) {
		return jdbc.query(CANDIDATE + " WHERE c.id=?" + (lock ? " FOR UPDATE OF c" : ""), this::candidate, id).stream()
				.findFirst().orElseThrow(() -> new ReviewException(NOT_FOUND));
	}
	private Candidate candidate(ResultSet r, int n) throws SQLException {
		return new Candidate(r.getLong("id"), Kind.valueOf(r.getString("kind")), r.getString("category"),
				r.getString("expression"), r.getString("reason"), r.getString("intent"), r.getLong("revision"),
				r.getString("status"), r.getObject("word_id", Long.class), r.getString("embedding_profile"),
				r.getLong("occurrences"), r.getLong("unread"));
	}
	public Long exact(Suggestion s, String hash) {
		return jdbc.query(
				"SELECT id FROM guard_review_candidates WHERE kind=? AND (kind='WORD' OR (category=? AND intent=?)) AND normalized_hash=?",
				(r, n) -> r.getLong(1), s.kind().name(), s.category(), s.intent(), hash).stream().findFirst()
				.orElse(null);
	}
	public List<Long> similar(Suggestion s, Vector vector, double threshold) {
		if (vector == null)
			return List.of();
		return jdbc.query("""
				SELECT id FROM guard_review_candidates WHERE kind='WARNING' AND category=? AND intent=?
				AND embedding_profile=? AND 1-(embedding <=> ?::vector) >= ?
				ORDER BY embedding <=> ?::vector LIMIT 5
				""", (r, n) -> r.getLong(1), s.category(), s.intent(), vector.profile(),
				new PGvector(vector.values()).toString(), threshold, new PGvector(vector.values()).toString());
	}
	public long insertCandidate(Suggestion s, String hash, Vector vector) {
		var inserted = jdbc.query(
				"""
						INSERT INTO guard_review_candidates(kind,category,expression,reason,intent,normalized_hash,embedding_profile,embedding)
						VALUES (?,?,?,?,?,?,?,?::vector) ON CONFLICT DO NOTHING RETURNING id
						""",
				(r, n) -> r.getLong(1), s.kind().name(), s.category(), s.expression(), s.reason(), s.intent(), hash,
				vector == null ? null : vector.profile(),
				vector == null ? null : new PGvector(vector.values()).toString());
		return inserted.isEmpty() ? exact(s, hash) : inserted.getFirst();
	}
	public void finding(long candidateId, long runId, Source source, Suggestion s) {
		jdbc.update(
				"""
						INSERT INTO guard_review_findings(candidate_id,first_run_id,source_id,source_key,source_fingerprint,stage,category)
						SELECT ?,?,source_id,source_key,source_fingerprint,?,? FROM guard_review_items WHERE id=?
						ON CONFLICT(candidate_id,source_key,source_fingerprint,stage,category) DO NOTHING
						""",
				candidateId, runId, s.stage().name(), s.category(), source.id());
	}
	public Long existingWarning(Source source, Suggestion s) {
		return jdbc.query("""
				SELECT f.candidate_id FROM guard_review_findings f JOIN guard_review_candidates c ON c.id=f.candidate_id
				JOIN guard_review_items i ON i.id=? AND i.source_fingerprint=f.source_fingerprint
				WHERE f.source_key=? AND f.stage=? AND f.category=? AND c.kind='WARNING' AND c.intent=? LIMIT 1
				""", (r, n) -> r.getLong(1), source.id(), source.sourceKey(), s.stage().name(), s.category(),
				s.intent()).stream().findFirst().orElse(null);
	}
	public List<java.util.Map<String, Object>> findings(long id) {
		return jdbc.queryForList(
				"""
						SELECT f.id,f.source_key AS "sourceKey",f.stage,f.category,
						CASE WHEN r.details_retained AND COALESCE(r.finished_at,r.created_at)>=LOCALTIMESTAMP-make_interval(days => ?)
						 THEN i.question_snapshot END AS question,
						CASE WHEN r.details_retained AND COALESCE(r.finished_at,r.created_at)>=LOCALTIMESTAMP-make_interval(days => ?)
						 THEN i.answer_snapshot END AS answer,
						(r.details_retained AND COALESCE(r.finished_at,r.created_at)>=LOCALTIMESTAMP-make_interval(days => ?)) AS "detailsRetained",
						f.created_at AS "createdAt"
						FROM guard_review_findings f JOIN guard_review_runs r ON r.id=f.first_run_id
						LEFT JOIN guard_review_items i ON i.run_id=f.first_run_id AND i.source_key=f.source_key
						 AND i.source_fingerprint=f.source_fingerprint
						WHERE f.candidate_id=? ORDER BY f.id DESC LIMIT 200
						""",
				RETENTION.detailDays(), RETENTION.detailDays(), RETENTION.detailDays(), id);
	}

	/** 종료된 자료만 작은 묶음으로 정리합니다. 활성 후보·발견 관계·알림 중복키는 삭제하지 않습니다. */
	public int expireRunDetails(int limit) {
		return jdbc.update(
				"""
						UPDATE guard_review_runs SET updated_at=CURRENT_TIMESTAMP,request='{"expired":true}'::jsonb,result=result-'cases',details_retained=false
						WHERE id IN (SELECT id FROM guard_review_runs
						 WHERE status IN ('COMPLETED','FAILED') AND details_retained
						 AND COALESCE(finished_at,created_at)<LOCALTIMESTAMP-make_interval(days => ?)
						 ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
						""",
				RETENTION.detailDays(), limit);
	}
	public int clearSourceSnapshots(int limit) {
		return jdbc.update(
				"""
						UPDATE guard_review_items SET updated_at=CURRENT_TIMESTAMP,question_snapshot=NULL,answer_snapshot=NULL
						WHERE id IN (SELECT i.id FROM guard_review_items i JOIN guard_review_runs r ON r.id=i.run_id
						 WHERE r.status IN ('COMPLETED','FAILED') AND NOT r.details_retained
						 AND i.status IN ('COMPLETED','FAILED') AND (i.question_snapshot IS NOT NULL OR i.answer_snapshot IS NOT NULL)
						 ORDER BY i.id LIMIT ? FOR UPDATE OF i SKIP LOCKED)
						""",
				limit);
	}
	public int pruneItems(int limit) {
		return jdbc.update("""
				DELETE FROM guard_review_items WHERE id IN (
				 SELECT i.id FROM guard_review_items i JOIN guard_review_runs r ON r.id=i.run_id
				 WHERE r.status IN ('COMPLETED','FAILED') AND NOT r.details_retained
				 AND COALESCE(r.finished_at,r.created_at)<LOCALTIMESTAMP-make_interval(days => ?)
				 AND (i.status<>'COMPLETED' OR EXISTS (SELECT 1 FROM guard_review_items newer
				  WHERE newer.source_key=i.source_key AND newer.status='COMPLETED'
				  AND (newer.finished_at,newer.id)>(i.finished_at,i.id)))
				 ORDER BY i.id LIMIT ? FOR UPDATE OF i SKIP LOCKED)
				""", RETENTION.summaryDays(), limit);
	}
	public int pruneRuns(int limit) {
		return jdbc.update(
				"""
						DELETE FROM guard_review_runs WHERE id IN (
						 SELECT r.id FROM guard_review_runs r WHERE r.status IN ('COMPLETED','FAILED') AND NOT r.details_retained
						 AND COALESCE(r.finished_at,r.created_at)<LOCALTIMESTAMP-make_interval(days => ?)
						 AND NOT EXISTS (SELECT 1 FROM guard_review_items i WHERE i.run_id=r.id)
						 AND NOT EXISTS (SELECT 1 FROM guard_review_findings f WHERE f.first_run_id=r.id)
						 ORDER BY r.id LIMIT ? FOR UPDATE OF r SKIP LOCKED)
						""",
				RETENTION.summaryDays(), limit);
	}
	public int pruneHistory(int limit) {
		return jdbc.update("""
				DELETE FROM guard_review_history WHERE id IN (
				 SELECT h.id FROM guard_review_history h WHERE h.created_at<LOCALTIMESTAMP-make_interval(days => ?)
				 AND EXISTS (SELECT 1 FROM guard_review_history newer
				  WHERE newer.candidate_id=h.candidate_id AND newer.action=h.action AND newer.id>h.id)
				 ORDER BY h.id LIMIT ? FOR UPDATE OF h SKIP LOCKED)
				""", RETENTION.historyDays(), limit);
	}
	public void edit(long id, String text, String reason, String hash, Long wordId) {
		jdbc.update(
				"UPDATE guard_review_candidates SET updated_at=CURRENT_TIMESTAMP,expression=?,reason=?,normalized_hash=?,word_id=?,revision=revision+1,status='PENDING' WHERE id=?",
				text, reason, hash, wordId, id);
	}
	public void status(long id, String status, Long wordId) {
		jdbc.update("UPDATE guard_review_candidates SET updated_at=CURRENT_TIMESTAMP,status=?,word_id=? WHERE id=?",
				status, wordId, id);
	}
	public void audit(Candidate c, String action, Long adminId) {
		jdbc.update(
				"INSERT INTO guard_review_history(candidate_id,revision,action,admin_id,expression) VALUES (?,?,?,?,?)",
				c.id(), c.revision(), action, adminId, c.expression());
	}
	public List<History> history() {
		return jdbc.query("SELECT * FROM guard_review_history ORDER BY id DESC LIMIT 200",
				(r, n) -> new History(r.getLong("id"), r.getLong("candidate_id"), r.getLong("revision"),
						r.getString("action"), r.getObject("admin_id", Long.class), r.getString("expression"),
						r.getObject("created_at", LocalDateTime.class)));
	}
	public List<AlertSummary> pendingAlerts() {
		return jdbc.query("""
				SELECT f.candidate_id,count(*) FILTER(WHERE f.notified_at IS NULL) AS new_count,count(*) AS total_count
				FROM guard_review_findings f JOIN guard_review_candidates c ON c.id=f.candidate_id
				WHERE c.kind='WARNING' GROUP BY f.candidate_id HAVING count(*) FILTER(WHERE f.notified_at IS NULL)>0
				""", (r, n) -> new AlertSummary(r.getLong(1), r.getInt(2), r.getLong(3)));
	}
	public long alertGeneration(long id) {
		return jdbc.queryForObject(
				"SELECT max(id) FROM guard_review_findings WHERE candidate_id=? AND notified_at IS NULL", Long.class,
				id);
	}
	public List<Long> admins() {
		return jdbc.queryForList("SELECT user_id FROM users WHERE role='ADMIN' AND deleted_at IS NULL", Long.class);
	}
	public void attachNotification(long candidateId, String dedupeKey) {
		jdbc.update("UPDATE notifications SET guard_review_candidate_id=? WHERE dedupe_key=?", candidateId, dedupeKey);
	}
	public void markNotified(long candidateId, long generation) {
		jdbc.update(
				"UPDATE guard_review_findings SET notified_at=LOCALTIMESTAMP WHERE candidate_id=? AND id<=? AND notified_at IS NULL",
				candidateId, generation);
	}
}
