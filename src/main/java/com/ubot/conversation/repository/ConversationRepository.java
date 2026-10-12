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
					       (SELECT a.question
					          FROM answer_attempts_history a
					         WHERE a.conversation_id = c.conversation_id
					           AND a.source_attempt_id IS NULL
					         ORDER BY a.created_at, a.attempt_id
					         LIMIT 1) AS title,
					       c.updated_at AS lastQuestionAt
					  FROM conversations c
					 WHERE c.user_id = :userId
					   AND EXISTS (SELECT 1 FROM answer_attempts_history a WHERE a.conversation_id = c.conversation_id)
					 ORDER BY c.updated_at DESC, c.conversation_id DESC
					""",
			countQuery = """
					SELECT count(*)
					  FROM conversations c
					 WHERE c.user_id = :userId
					   AND EXISTS (SELECT 1 FROM answer_attempts_history a WHERE a.conversation_id = c.conversation_id)
					""",
			nativeQuery = true)
	Page<ConversationSummary> findSummaryListByUserId(@Param("userId") Long userId, Pageable pageable);

	interface ConversationSummary {
		UUID getPublicId();

		String getTitle();

		LocalDateTime getLastQuestionAt();
	}
}
