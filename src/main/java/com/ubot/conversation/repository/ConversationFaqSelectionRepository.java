package com.ubot.conversation.repository;

import com.ubot.conversation.entity.ConversationFaqSelection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationFaqSelectionRepository extends JpaRepository<ConversationFaqSelection, UUID> {
	List<ConversationFaqSelection> findAllByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId);
}
