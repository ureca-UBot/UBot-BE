package com.ubot.counsel.session.repository;

import com.ubot.counsel.common.exception.CounselErrorCode;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.counsel.session.entity.Session;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
	private final JdbcTemplate jdbc;
	private static final RowMapper<Session> MAPPER = (r, n) -> new Session(r.getObject("id", UUID.class),
			r.getLong("customer_id"), r.getObject("counselor_id", Long.class), r.getString("status"),
			r.getLong("assignment_version"), r.getLong("last_delivered_seq"),
			instant(r, "created_at"), instant(r, "paused_at"), instant(r, "ended_at"),
			r.getObject("conversation_id", UUID.class), r.getObject("handoff_id", UUID.class));
	public SessionRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}
	private static Instant instant(ResultSet r, String column) throws SQLException {
		var t = r.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}
	// All lifecycle writers acquire this transaction-scoped lock before any session/presence lock.
	// Small MVP queue: one shared PostgreSQL lock works across multiple service instances.
	public void lockQueue() { jdbc.execute("SELECT pg_advisory_xact_lock(2001020)"); }
	public Session get(UUID id, boolean lock) {
		var rows = jdbc.query("SELECT * FROM counseling.sessions WHERE id = ?" + (lock ? " FOR UPDATE" : ""), MAPPER, id);
		if (rows.isEmpty()) throw new CounselException(CounselErrorCode.SESSION_NOT_FOUND);
		return rows.getFirst();
	}
	public Session current(long userId, boolean counselor) {
		var rows = jdbc.query("SELECT * FROM counseling.sessions WHERE "
				+ (counselor ? "counselor_id = ? AND status IN ('ACTIVE','PAUSED')" : "customer_id = ? AND status <> 'ENDED'"),
				MAPPER, userId);
		return rows.isEmpty() ? null : rows.getFirst();
	}
	public UUID createHandoff(long customer, UUID conversation, UUID handoff) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO counseling.sessions(id,customer_id,status,conversation_id,handoff_id) VALUES (?,?,'WAITING',?,?)
				""", id, customer, conversation, handoff);
		return id;
	}
	public Session handoff(UUID handoff) {
		var rows = jdbc.query("SELECT * FROM counseling.sessions WHERE handoff_id=?", MAPPER, handoff);
		return rows.isEmpty() ? null : rows.getFirst();
	}
	public Session latest(long customer, UUID conversation) {
		var rows = jdbc.query("""
				SELECT * FROM counseling.sessions WHERE customer_id=? AND conversation_id=?
				ORDER BY created_at DESC,id DESC LIMIT 1
				""", MAPPER, customer, conversation);
		return rows.isEmpty() ? null : rows.getFirst();
	}
	public void presence(long user, String status) {
		jdbc.update("""
				INSERT INTO counseling.counselor_presence(user_id,status,last_heartbeat_at) VALUES (?,?,now())
				ON CONFLICT (user_id) DO UPDATE SET status=excluded.status, last_heartbeat_at=now()
				""", user, status);
	}
	public String presenceStatus(long user) {
		var rows = jdbc.queryForList("SELECT status FROM counseling.counselor_presence WHERE user_id=?", String.class, user);
		return rows.isEmpty() ? "OFFLINE" : rows.getFirst();
	}
	public void heartbeat(long user) {
		jdbc.update("UPDATE counseling.counselor_presence SET last_heartbeat_at=now() WHERE user_id=?", user);
	}
	public void maintain(int heartbeatSeconds, int reconnectSeconds) {
		// Deleted/demoted counselors cannot be assigned, even if an old browser keeps running.
		jdbc.update("""
				UPDATE counseling.counselor_presence p SET status='OFFLINE'
				WHERE NOT EXISTS (SELECT 1 FROM public.users u WHERE u.user_id=p.user_id
								   AND u.role='COUNSELOR' AND u.deleted_at IS NULL)
				""");
		jdbc.update("""
				UPDATE counseling.sessions s SET status='PAUSED',
				  paused_at=LEAST(now(), p.last_heartbeat_at + (? * interval '1 second'))
				FROM counseling.counselor_presence p
				WHERE s.counselor_id=p.user_id AND s.status='ACTIVE'
				  AND (p.status='OFFLINE' OR p.last_heartbeat_at < now()-(? * interval '1 second'))
				""", heartbeatSeconds, heartbeatSeconds);
		jdbc.update("""
				UPDATE counseling.sessions SET status='WAITING', counselor_id=NULL, paused_at=NULL,
				  assignment_version=assignment_version+1, waiting_since=now()
				WHERE status='PAUSED' AND paused_at <= now()-(? * interval '1 second')
				""", reconnectSeconds);
		jdbc.update("""
				UPDATE counseling.sessions s SET status='ACTIVE', paused_at=NULL
				FROM counseling.counselor_presence p
				WHERE s.counselor_id=p.user_id AND s.status='PAUSED' AND p.status='AVAILABLE'
				  AND p.last_heartbeat_at >= now()-(? * interval '1 second')
				""", heartbeatSeconds);
		jdbc.update("""
				UPDATE counseling.sessions s SET status='ENDED', ended_at=now(), assignment_version=assignment_version+1
				WHERE s.status<>'ENDED' AND NOT EXISTS
				  (SELECT 1 FROM public.users u WHERE u.user_id=s.customer_id AND u.deleted_at IS NULL AND u.role='USER')
				""");
	}
	public void assign(int heartbeatSeconds) {
		var waiting = jdbc.queryForList("""
				SELECT id FROM counseling.sessions WHERE status='WAITING' ORDER BY waiting_since,id
				""", UUID.class);
		var counselors = jdbc.queryForList("""
				SELECT p.user_id FROM counseling.counselor_presence p
				JOIN public.users u ON u.user_id=p.user_id AND u.role='COUNSELOR' AND u.deleted_at IS NULL
				WHERE p.status='AVAILABLE' AND p.last_heartbeat_at >= now()-(? * interval '1 second')
				  AND NOT EXISTS (SELECT 1 FROM counseling.sessions s WHERE s.counselor_id=p.user_id
								  AND s.status IN ('ACTIVE','PAUSED'))
				ORDER BY p.last_heartbeat_at,p.user_id
				""", Long.class, heartbeatSeconds);
		for (int i = 0; i < Math.min(waiting.size(), counselors.size()); i++) {
			jdbc.update("""
					UPDATE counseling.sessions SET status='ACTIVE', counselor_id=?, assignment_version=assignment_version+1
					WHERE id=? AND status='WAITING'
					""", counselors.get(i), waiting.get(i));
		}
	}
	public void end(UUID id) {
		int changed = jdbc.update("UPDATE counseling.sessions SET status='ENDED', ended_at=now(), assignment_version=assignment_version+1 WHERE id=? AND status<>'ENDED'", id);
		if (changed > 0) touchConversation(id);
	}
	public void touchConversation(UUID id) {
		jdbc.update("""
				UPDATE public.conversations c SET updated_at=GREATEST(c.updated_at,LOCALTIMESTAMP)
				FROM counseling.sessions s WHERE s.id=? AND c.public_id=s.conversation_id
				""", id);
	}
	public long nextSequence(UUID id) {
		return jdbc.queryForObject("""
				UPDATE counseling.sessions SET last_delivered_seq=last_delivered_seq+1 WHERE id=? RETURNING last_delivered_seq
				""", Long.class, id);
	}
}
