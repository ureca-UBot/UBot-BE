package com.ubot.counsel.session.repository;

import com.ubot.counsel.session.dto.response.HistoryMessageResponseDto;
import java.util.List;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HistoryRepository {
	private final JdbcTemplate jdbc;
	public HistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
	public void save(UUID session, List<HistoryMessageResponseDto> history) {
		jdbc.batchUpdate("""
				INSERT INTO counseling.session_history(session_id,sequence,role,content,source,origin_session_id,origin_sequence)
				VALUES (?,?,?,?,?,?,?)
				""", history, 100, (statement, item) -> {
			statement.setObject(1, session);
			statement.setLong(2, item.sequence());
			statement.setString(3, item.role());
			statement.setString(4, item.content());
			statement.setString(5, item.source());
		statement.setObject(6, item.originSessionId() == null ? session : item.originSessionId());
		statement.setLong(7, item.originSequence() == null ? item.sequence() : item.originSequence());
		});
	}
	public List<HistoryMessageResponseDto> get(UUID session, long after, int limit) {
		return jdbc.query("""
				SELECT sequence,role,content,source,origin_session_id,origin_sequence FROM counseling.session_history
				WHERE session_id=? AND sequence>? ORDER BY sequence LIMIT ?
				""", (r, n) -> new HistoryMessageResponseDto(r.getLong(1), r.getString(2), r.getString(3), r.getString(4),
						r.getObject(5, UUID.class), r.getObject(6, Long.class)),
				session, after, limit);
	}
	public List<HistoryMessageResponseDto> reuseScreenOrigins(UUID conversation, List<HistoryMessageResponseDto> snapshot) {
		record Key(String role, String content) {}
		var origins = new HashMap<Key, ArrayDeque<HistoryMessageResponseDto>>();
		jdbc.query("""
				SELECT h.* FROM counseling.session_history h JOIN counseling.sessions s ON s.id=h.session_id
				WHERE s.conversation_id=? AND h.source='SCREEN'
				  AND h.session_id=h.origin_session_id AND h.sequence=h.origin_sequence
				ORDER BY s.created_at,s.id,h.sequence
				""", (r, n) -> new HistoryMessageResponseDto(r.getLong("sequence"), r.getString("role"),
						r.getString("content"), "SCREEN", r.getObject("origin_session_id", UUID.class),
						r.getLong("origin_sequence")), conversation)
				.forEach(item -> origins.computeIfAbsent(new Key(item.role(), item.content()), ignored -> new ArrayDeque<>()).add(item));
		return snapshot.stream().map(item -> {
			var matches = origins.get(new Key(item.role(), item.content()));
			if (!item.source().equals("SCREEN") || item.originSessionId() != null || matches == null || matches.isEmpty()) return item;
			var origin = matches.removeFirst();
			return new HistoryMessageResponseDto(item.sequence(), item.role(), item.content(), item.source(),
					origin.originSessionId(), origin.originSequence());
		}).toList();
	}
	public List<HistoryMessageResponseDto> previousCounsel(long customer, UUID conversation) {
		if (conversation == null) return List.of();
		return jdbc.query("""
				SELECT m.sender_role,m.content FROM counseling.messages m JOIN counseling.sessions s ON s.id=m.session_id
				WHERE s.customer_id=? AND s.conversation_id=? AND m.status='ALLOWED'
				ORDER BY s.created_at,s.id,m.delivery_sequence
				""", (r, n) -> new HistoryMessageResponseDto(0, r.getString(1), r.getString(2), "COUNSEL"), customer, conversation);
	}
	public List<HistoryMessageResponseDto> delivered(UUID session) {
		return jdbc.query("""
				SELECT sender_role,content FROM counseling.messages WHERE session_id=? AND status='ALLOWED'
				ORDER BY delivery_sequence
				""", (r, n) -> new HistoryMessageResponseDto(0, r.getString(1), r.getString(2), "COUNSEL"), session);
	}
}
