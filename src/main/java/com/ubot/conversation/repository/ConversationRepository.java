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

	Optional<Conversation> findByPublicIdAndUserIdAndDeletedAtIsNull(UUID publicId, Long userId);

	Optional<Conversation> findByIdAndDeletedAtIsNull(Long conversationId);

	boolean existsByIdAndDeletedAtIsNull(Long conversationId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Conversation c where c.publicId = :publicId and c.userId = :userId")
	Optional<Conversation> findMemberConversationForLock(
			@Param("publicId") UUID publicId, @Param("userId") Long userId);

	@Query(
			value = """
					SELECT c.public_id AS publicId,
					       (SELECT q.user_question
					          FROM question_log q
					         WHERE q.conversation_id = c.conversation_id
					         ORDER BY q.created_at, q.id
					         LIMIT 1) AS title,
					       c.updated_at AS lastQuestionAt
					  FROM conversations c
					 WHERE c.user_id = :userId
					   AND c.deleted_at IS NULL
					   AND EXISTS (SELECT 1 FROM question_log q WHERE q.conversation_id = c.conversation_id)
					 ORDER BY c.updated_at DESC, c.conversation_id DESC
					""",
			countQuery = """
					SELECT count(*)
					  FROM conversations c
					 WHERE c.user_id = :userId
					   AND c.deleted_at IS NULL
					   AND EXISTS (SELECT 1 FROM question_log q WHERE q.conversation_id = c.conversation_id)
					""",
			nativeQuery = true)
	Page<ConversationSummary> findSummaryListByUserId(@Param("userId") Long userId, Pageable pageable);

	interface ConversationSummary {
		UUID getPublicId();

		String getTitle();

		LocalDateTime getLastQuestionAt();
	}
}
