package com.ubot.counsel.history.repository;

import com.ubot.counsel.history.dto.response.ConversationTimelineResponseDto;
import com.ubot.counsel.history.model.TimelineCursor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ConversationTimelineRepository {
	private final JdbcTemplate jdbcTemplate;

	public List<ConversationTimelineResponseDto> findTimeline(UUID conversationId, TimelineCursor after, int limit) {
		var cursor = after == null ? new TimelineCursor(Instant.EPOCH, 0, TimelineCursor.NO_SESSION, 0, 0, "") : after;
		// 저장된 AI 문답과 상담 메시지는 원본을 읽고, 화면에만 있던 문답은 최초 인계 사본을 한 번만 읽음
		return jdbcTemplate.query("""
				WITH conversation AS (
				    SELECT conversation_id,public_id FROM public.conversations WHERE public_id=?
				), rooms AS (
				    SELECT s.* FROM counseling.sessions s JOIN conversation c ON c.public_id=s.conversation_id
				), entries AS (
				    SELECT 'q:' || q.id || ':user' AS id,NULL::uuid AS session_id,'USER' AS role,
				           q.user_question AS content,'SAVED' AS source,q.created_at::timestamptz AS occurred_at,
				           10 AS position,q.id AS item_order,0 AS part
				    FROM public.question_log q JOIN conversation c ON c.conversation_id=q.conversation_id
				    WHERE q.sub_question_index=1
				    UNION ALL
				    SELECT 'q:' || q.id || ':ai',NULL::uuid,'AI',q.llm_question,'SAVED',q.created_at::timestamptz,10,q.id,1
				    FROM public.question_log q JOIN conversation c ON c.conversation_id=q.conversation_id
				    WHERE q.sub_question_index=1 AND q.llm_question IS NOT NULL AND q.llm_question<>''
				    UNION ALL
				    SELECT 'f:' || f.selection_id || ':user',NULL::uuid,'USER',f.question,'SAVED',f.created_at::timestamptz,12,0,0
				    FROM public.conversation_faq_selections f JOIN conversation c ON c.conversation_id=f.conversation_id
				    UNION ALL
				    SELECT 'f:' || f.selection_id || ':ai',NULL::uuid,'AI',f.answer,'SAVED',f.created_at::timestamptz,12,0,1
				    FROM public.conversation_faq_selections f JOIN conversation c ON c.conversation_id=f.conversation_id
				    UNION ALL
				    SELECT 'h:' || h.session_id || ':' || h.sequence,h.session_id,h.role,h.content,h.source,
				           s.created_at,15,h.sequence,0
				    FROM counseling.session_history h JOIN rooms s ON s.id=h.session_id
				    WHERE h.session_id=h.origin_session_id AND h.sequence=h.origin_sequence
				      AND (h.source='SCREEN'
				           OR (h.source='SAVED' AND NOT EXISTS (
				               SELECT 1 FROM public.question_log q JOIN conversation c ON c.conversation_id=q.conversation_id
				               WHERE q.sub_question_index=1 AND ((h.role='USER' AND q.user_question=h.content)
				                   OR (h.role='AI' AND q.llm_question=h.content)))
				               AND NOT EXISTS (SELECT 1 FROM public.conversation_faq_selections f
				                   JOIN conversation c ON c.conversation_id=f.conversation_id
				                   WHERE (h.role='USER' AND f.question=h.content) OR (h.role='AI' AND f.answer=h.content)))
				           OR (h.source='COUNSEL' AND NOT EXISTS (
				               SELECT 1 FROM counseling.messages m JOIN rooms previous ON previous.id=m.session_id
				               WHERE m.status='ALLOWED' AND m.sender_role=h.role AND m.content=h.content
				                 AND m.decided_at<=s.created_at)))
				    UNION ALL
				    SELECT 's:' || s.id || ':start',s.id,'SYSTEM','상담사 연결 요청','COUNSEL',s.created_at,20,0,0 FROM rooms s
				    UNION ALL
				    SELECT 'm:' || m.id,m.session_id,m.sender_role,m.content,'COUNSEL',m.decided_at,30,m.delivery_sequence,0
				    FROM counseling.messages m JOIN rooms s ON s.id=m.session_id WHERE m.status='ALLOWED'
				    UNION ALL
				    SELECT 's:' || s.id || ':end',s.id,'SYSTEM','상담 종료','COUNSEL',s.ended_at,40,0,0
				    FROM rooms s WHERE s.status='ENDED'
				), numbered AS (
				    SELECT *,row_number() OVER (ORDER BY occurred_at,position,COALESCE(session_id,'00000000-0000-0000-0000-000000000000'::uuid),item_order,part,id) AS sequence
				    FROM entries
				)
				SELECT * FROM numbered WHERE ? OR
				    (occurred_at,position,COALESCE(session_id,'00000000-0000-0000-0000-000000000000'::uuid),item_order,part,id)
				    > (CAST(? AS timestamptz),?,CAST(? AS uuid),?,?,?)
				ORDER BY occurred_at,position,COALESCE(session_id,'00000000-0000-0000-0000-000000000000'::uuid),item_order,part,id LIMIT ?
				""", (row, index) -> new ConversationTimelineResponseDto(
						row.getLong("sequence"), row.getString("id"), row.getObject("session_id", UUID.class),
						row.getString("role"), row.getString("content"), row.getString("source"),
						row.getTimestamp("occurred_at").toInstant(),
						new TimelineCursor(row.getTimestamp("occurred_at").toInstant(), row.getInt("position"),
								row.getObject("session_id", UUID.class) == null ? TimelineCursor.NO_SESSION : row.getObject("session_id", UUID.class),
								row.getLong("item_order"), row.getInt("part"), row.getString("id")).encode()),
				conversationId, after == null, cursor.occurredAt().toString(), cursor.position(), cursor.sessionId(),
				cursor.itemOrder(), cursor.part(), cursor.id(), limit);
	}
}
