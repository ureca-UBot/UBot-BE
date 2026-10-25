package com.ubot.guardreview.repository;

import com.ubot.guardreview.dto.response.ReviewCheckResponseDto;
import com.ubot.guardreview.dto.response.ReviewCheckDetailResponseDto;
import com.ubot.guardreview.dto.response.ReviewCheckReassessmentResponseDto;

import static com.ubot.guardreview.model.ReviewModels.RETENTION;
import static com.ubot.guardreview.model.ReviewCheckModels.*;

import com.ubot.common.PageResponseDto;
import com.ubot.guardreview.exception.ReviewException;
import com.ubot.guardreview.exception.ReviewErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class ReviewCheckRepository {
	private final JdbcTemplate jdbc;
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String AVAILABLE = "details_retained AND created_at>=LOCALTIMESTAMP-make_interval(days => "
			+ RETENTION.detailDays() + ")";
	private static final String SUMMARY = "id,attempt_id,origin,outcome,reason_codes::text,created_at,"
			+ AVAILABLE + " AS available,CASE WHEN " + AVAILABLE + " THEN question_snapshot END AS question,"
			+ "CASE WHEN " + AVAILABLE + " THEN left(answer_snapshot,240) END AS preview";

	public void save(UUID key, Long attemptId, Origin origin, Outcome outcome, List<String> reasons,
			String question, String answer, String finalAnswer, String explanation, String context) {
		jdbc.update("""
				INSERT INTO guard_review_checks(capture_key,attempt_id,origin,outcome,reason_codes,
				 question_snapshot,answer_snapshot,final_answer_snapshot,explanation,context_snapshot)
				VALUES (?,?,?,?,?::jsonb,COALESCE((SELECT question FROM answer_attempts_history WHERE attempt_id=?),?),?,?,?,?::jsonb)
				ON CONFLICT(capture_key) DO NOTHING
				""", key, attemptId, origin.name(), outcome.name(), JSON.writeValueAsString(reasons),
				attemptId, question, answer, finalAnswer, explanation, context);
	}

	public PageResponseDto<ReviewCheckResponseDto> page(Origin origin, Outcome outcome, int page, int size) {
		List<Object> parameters = new ArrayList<>();
		String where = " WHERE created_at>=LOCALTIMESTAMP-make_interval(days => " + RETENTION.summaryDays() + ")";
		if (origin != null) { where += " AND origin=?"; parameters.add(origin.name()); }
		if (outcome != null) { where += " AND outcome=?"; parameters.add(outcome.name()); }
		Long total = jdbc.queryForObject("SELECT count(*) FROM guard_review_checks" + where, Long.class,
				parameters.toArray());
		parameters.add(size);
		parameters.add((long) page * size);
		List<ReviewCheckResponseDto> rows = jdbc.query("SELECT " + SUMMARY + " FROM guard_review_checks" + where
				+ " ORDER BY id DESC LIMIT ? OFFSET ?", this::summary, parameters.toArray());
		return PageResponseDto.of(rows, page, size, total == null ? 0 : total);
	}

	public void saveSkippedIfAbsent(long attemptId, String question, Origin origin) {
		UUID key = UUID.nameUUIDFromBytes(("skipped:" + attemptId + ":" + origin).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		jdbc.update("""
				INSERT INTO guard_review_checks(capture_key,attempt_id,origin,outcome,reason_codes,question_snapshot)
				SELECT ?,?,?,'SKIPPED','["NOT_CALLED"]'::jsonb,?
				WHERE NOT EXISTS (SELECT 1 FROM guard_review_checks WHERE attempt_id=? AND origin=?)
				ON CONFLICT(capture_key) DO NOTHING
				""", key, attemptId, origin.name(), question, attemptId, origin.name());
	}

	public ReviewCheckDetailResponseDto detail(long id) {
		ReviewCheckDetailResponseDto detail = jdbc.query("SELECT " + SUMMARY + ",CASE WHEN " + AVAILABLE
				+ " THEN answer_snapshot END AS answer,CASE WHEN " + AVAILABLE
				+ " THEN final_answer_snapshot END AS final_answer,CASE WHEN " + AVAILABLE
				+ " THEN explanation END AS explanation,CASE WHEN " + AVAILABLE
				+ " THEN context_snapshot::text END AS context FROM guard_review_checks WHERE id=?"
				+ " AND created_at>=LOCALTIMESTAMP-make_interval(days => " + RETENTION.summaryDays() + ")",
				(r, n) -> new ReviewCheckDetailResponseDto(summary(r, n), r.getString("answer"), r.getString("final_answer"),
						r.getString("explanation"), object(r.getString("context"))), id).stream().findFirst()
				.orElseThrow(() -> new ReviewException(ReviewErrorCode.NOT_FOUND));
		return new ReviewCheckDetailResponseDto(detail.check(), detail.answer(), detail.finalAnswer(), detail.explanation(), detail.context(),
				detail.check().detailsRetained() ? reassessments(id) : List.of());
	}
	/** 원래 검사 판정과 별도로, 동결된 각 분석의 상태와 재검토 의견을 보여줍니다. */
	public List<ReviewCheckReassessmentResponseDto> reassessments(long checkId) {
		return jdbc.query("""
				SELECT i.run_id,i.status,i.created_at,i.finished_at,i.reassessment::text
				FROM guard_review_items i JOIN guard_review_runs r ON r.id=i.run_id
				WHERE i.check_id=? AND i.guard_outcome='REVIEW' AND r.details_retained
				 AND (r.status NOT IN ('COMPLETED','FAILED')
				 OR COALESCE(r.finished_at,r.created_at)>=LOCALTIMESTAMP-make_interval(days => ?))
				ORDER BY i.id DESC LIMIT 10
				""", (row, index) -> new ReviewCheckReassessmentResponseDto(row.getLong("run_id"), row.getString("status"),
						row.getObject("created_at", LocalDateTime.class), row.getObject("finished_at", LocalDateTime.class),
						row.getString("reassessment") == null ? null : JSON.readValue(row.getString("reassessment"),
						com.ubot.guardreview.model.ReviewReassessment.class)), checkId, RETENTION.detailDays());
	}

	private ReviewCheckResponseDto summary(ResultSet r, int index) throws SQLException {
		return new ReviewCheckResponseDto(r.getLong("id"), r.getObject("attempt_id", Long.class),
				Origin.valueOf(r.getString("origin")), Outcome.valueOf(r.getString("outcome")),
				List.of(JSON.readValue(r.getString("reason_codes"), String[].class)), r.getString("question"),
				r.getString("preview"), r.getObject("created_at", LocalDateTime.class), r.getBoolean("available"));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> object(String value) {
		return value == null ? Map.of() : JSON.readValue(value, Map.class);
	}

	/** 상세 본문은 30일, 출처·결과 요약은 90일입니다. 후보의 출처는 findings에 별도로 남습니다. */
	public int expireDetails(int limit) {
		return jdbc.update("""
				UPDATE guard_review_checks SET question_snapshot=NULL,answer_snapshot=NULL,final_answer_snapshot=NULL,
				 explanation=NULL,context_snapshot=NULL,details_retained=false WHERE id IN
				 (SELECT id FROM guard_review_checks WHERE details_retained
				 AND created_at<LOCALTIMESTAMP-make_interval(days => ?) ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
				""", RETENTION.detailDays(), limit);
	}

	public int prune(int limit) {
		return jdbc.update("""
				DELETE FROM guard_review_checks WHERE id IN (SELECT id FROM guard_review_checks
				 WHERE created_at<LOCALTIMESTAMP-make_interval(days => ?) ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED)
				""", RETENTION.summaryDays(), limit);
	}
}
