package com.ubot.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "conversation_faq_selections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationFaqSelection {
	@Id
	@Column(name = "selection_id")
	private UUID id;
	private Long conversationId;
	private Long faqId;
	private String question;
	private String answer;
	private LocalDateTime createdAt;

	public ConversationFaqSelection(UUID id, Long conversationId, Long faqId, String question, String answer) {
		this.id = id;
		this.conversationId = conversationId;
		this.faqId = faqId;
		this.question = question;
		this.answer = answer;
		this.createdAt = LocalDateTime.now();
	}
}
