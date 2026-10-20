package com.ubot.counsel.admin.repository;

import com.ubot.counsel.admin.dto.response.SafetyEventResponseDto;
import com.ubot.counsel.admin.dto.response.SafetySummaryResponseDto;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminCounselRepository {
	private final JdbcTemplate jdbc;
	public AdminCounselRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}
	public List<SafetyEventResponseDto> events(String decision, Instant from, Instant to, int page) {
		return jdbc.query("""
				SELECT e.id, e.created_at AS "createdAt", m.session_id AS "sessionId", m.sender_role AS "senderRole",
				  e.decision, e.category, e.score, e.threshold, e.model_name AS "modelName",
				  e.profile_version AS "profileVersion", e.rule_version AS "ruleVersion",
				  e.error_code AS "errorCode", e.admin_review AS "adminReview"
				FROM counseling.safety_filter_events e JOIN counseling.messages m ON m.id=e.message_id
				WHERE e.created_at >= ? AND e.created_at < ? AND (?='' OR e.decision=?)
				ORDER BY e.created_at DESC, e.id DESC LIMIT 51 OFFSET ?
				""", (r, n) -> new SafetyEventResponseDto(r.getLong("id"), r.getTimestamp("createdAt").toInstant(),
				r.getObject("sessionId", UUID.class), r.getString("senderRole"), r.getString("decision"),
				r.getString("category"), r.getObject("score", Double.class), r.getDouble("threshold"),
				r.getString("modelName"), r.getString("profileVersion"), r.getString("ruleVersion"),
				r.getString("errorCode"), r.getString("adminReview")),
				Timestamp.from(from), Timestamp.from(to), decision, decision, page * 50);
	}
	public SafetySummaryResponseDto summary(Instant from, Instant to) {
		return jdbc.queryForObject("""
				SELECT count(*) AS total,
				  count(*) FILTER (WHERE decision='ALLOWED') AS allowed,
				  count(*) FILTER (WHERE decision='BLOCKED') AS blocked,
				  count(*) FILTER (WHERE decision='CHECK_FAILED') AS "checkFailed",
				  count(*) FILTER (WHERE admin_review='FALSE_POSITIVE') AS "falsePositives"
				FROM counseling.safety_filter_events WHERE created_at >= ? AND created_at < ?
				""", (r, n) -> new SafetySummaryResponseDto(r.getLong("total"), r.getLong("allowed"),
				r.getLong("blocked"), r.getLong("checkFailed"), r.getLong("falsePositives")),
				Timestamp.from(from), Timestamp.from(to));
	}
	public String lockRole(long userId) {
		var roles = jdbc.queryForList("SELECT role FROM public.users WHERE user_id=? AND deleted_at IS NULL FOR UPDATE", String.class, userId);
		return roles.isEmpty() ? null : roles.getFirst();
	}
	public boolean hasOpenSession(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS(SELECT 1 FROM counseling.sessions
				  WHERE (customer_id=? OR counselor_id=?) AND status<>'ENDED')
				""", Boolean.class, userId, userId));
	}
	public void changeRole(long adminId, long userId, String previous, String next) {
		jdbc.update("UPDATE public.users SET role=? WHERE user_id=?", next, userId);
		jdbc.update("""
				INSERT INTO counseling.role_audit(admin_id,user_id,previous_role,new_role) VALUES (?,?,?,?)
				""", adminId, userId, previous, next);
		jdbc.update("UPDATE counseling.counselor_presence SET status='OFFLINE' WHERE user_id=?", userId);
	}
}
