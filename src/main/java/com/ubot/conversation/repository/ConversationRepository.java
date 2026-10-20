package com.ubot.conversation.repository;

import com.ubot.conversation.entity.Conversation;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Conversation c where c.id = :conversationId")
	Optional<Conversation> findConversationForLock(@Param("conversationId") Long conversationId);

	Optional<Conversation> findByPublicIdAndUserId(UUID publicId, Long userId);

	@Query(
			value = """
					SELECT c.public_id AS publicId,
					       COALESCE((SELECT q.user_question
					          FROM question_log q
					         WHERE q.conversation_id = c.conversation_id
					         ORDER BY q.created_at, q.id
					         LIMIT 1),
					         (SELECT m.content FROM counseling.messages m JOIN counseling.sessions s ON s.id=m.session_id
					          WHERE s.conversation_id=c.public_id AND m.status='ALLOWED' AND m.sender_role='USER'
					          ORDER BY s.created_at,s.id,m.delivery_sequence LIMIT 1), '상담사 상담') AS title,
					       c.updated_at AS lastQuestionAt,
					       EXISTS (SELECT 1 FROM counseling.sessions s WHERE s.conversation_id=c.public_id) AS hasCounsel,
					       (SELECT left(recent.content, 100) FROM (
					          SELECT q.user_question AS content, q.created_at AS occurred_at, 1 AS priority, q.id
					          FROM question_log q WHERE q.conversation_id=c.conversation_id AND q.sub_question_index=1
					          UNION ALL
					          SELECT m.content, m.decided_at, 2, m.id
					          FROM counseling.messages m JOIN counseling.sessions s ON s.id=m.session_id
					          WHERE s.conversation_id=c.public_id AND m.status='ALLOWED'
					        ) recent ORDER BY recent.occurred_at DESC,recent.priority DESC,recent.id DESC LIMIT 1) AS preview
					  FROM conversations c
					 WHERE c.user_id = :userId
					   AND (EXISTS (SELECT 1 FROM question_log q WHERE q.conversation_id = c.conversation_id)
					        OR EXISTS (SELECT 1 FROM counseling.sessions s WHERE s.conversation_id=c.public_id))
					 ORDER BY c.updated_at DESC, c.conversation_id DESC
					""",
			countQuery = """
					SELECT count(*)
					  FROM conversations c
					 WHERE c.user_id = :userId
					   AND (EXISTS (SELECT 1 FROM question_log q WHERE q.conversation_id = c.conversation_id)
					        OR EXISTS (SELECT 1 FROM counseling.sessions s WHERE s.conversation_id=c.public_id))
					""",
			nativeQuery = true)
	Page<ConversationSummary> findSummaryListByUserId(@Param("userId") Long userId, Pageable pageable);

	interface ConversationSummary {
		UUID getPublicId();

		String getTitle();

		LocalDateTime getLastQuestionAt();

		boolean getHasCounsel();

		String getPreview();
	}
}
