package com.ubot.counsel.message.repository;

import com.ubot.counsel.auth.model.Caller;
import com.ubot.counsel.message.dto.response.MessageResponseDto;
import com.ubot.counsel.message.entity.Message;
import com.ubot.counsel.session.entity.Session;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {
	private final JdbcTemplate jdbc;
	private static final RowMapper<Message> MAPPER = (r, n) -> new Message(r.getLong("id"), r.getObject("session_id", UUID.class),
			r.getObject("sender_id", Long.class), r.getString("sender_role"), r.getObject("client_message_id", UUID.class),
			r.getString("content"), r.getString("status"), r.getObject("delivery_sequence", Long.class),
			r.getLong("assignment_version"), r.getTimestamp("created_at").toInstant());
	public MessageRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}
	public Message existing(UUID session, long sender, UUID clientId) {
		var rows = jdbc.query("SELECT * FROM counseling.messages WHERE session_id=? AND sender_id=? AND client_message_id=?",
				MAPPER, session, sender, clientId);
		return rows.isEmpty() ? null : rows.getFirst();
	}
	public Message get(long id) {
		return jdbc.queryForObject("SELECT * FROM counseling.messages WHERE id=?", MAPPER, id);
	}
	public Message insert(Session session, Caller sender, UUID clientId, String content) {
		return jdbc.queryForObject("""
				INSERT INTO counseling.messages(session_id,sender_id,sender_role,assignment_version,client_message_id,content,status)
				VALUES (?,?,?,?,?,?,'PENDING') RETURNING *
				""", MAPPER, session.sessionId(), sender.id(), sender.role(), session.assignmentVersion(), clientId, content);
	}
	public int incrementRequestCount(long userId, int windowSeconds) {
		return jdbc.queryForObject("""
				INSERT INTO counseling.rate_buckets(user_id) VALUES (?)
				ON CONFLICT(user_id) DO UPDATE SET
				  requests=CASE WHEN counseling.rate_buckets.window_start < now()-(? * interval '1 second') THEN 1 ELSE counseling.rate_buckets.requests+1 END,
				  window_start=CASE WHEN counseling.rate_buckets.window_start < now()-(? * interval '1 second') THEN now() ELSE counseling.rate_buckets.window_start END
				RETURNING requests
				""", Integer.class, userId, windowSeconds, windowSeconds);
	}
	public void decide(long id, String status, Long sequence) {
		jdbc.update("UPDATE counseling.messages SET status=?,delivery_sequence=?,decided_at=now() WHERE id=? AND status='PENDING'",
				status, sequence, id);
	}
	public List<MessageResponseDto> delivered(UUID session, long after, int limit) {
		// This is the only participant history query. Never return internal messages or events.
		return jdbc.query("""
				SELECT * FROM counseling.messages WHERE session_id=? AND status='ALLOWED'
				AND delivery_sequence>? ORDER BY delivery_sequence LIMIT ?
				""", MAPPER, session, after, limit).stream().map(Message::delivery).toList();
	}
	public List<Message> stale(int seconds) {
		return jdbc.query("""
				SELECT * FROM counseling.messages WHERE status='PENDING'
				AND created_at < now()-(? * interval '1 second') ORDER BY created_at LIMIT 100
				""", MAPPER, seconds);
	}
}
