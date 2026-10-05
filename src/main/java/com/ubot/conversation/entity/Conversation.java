package com.ubot.conversation.entity;

import com.ubot.conversation.enums.ConversationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "conversations")
public class Conversation {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "conversation_id")
	private Long id;

	@Column(name = "public_id")
	private UUID publicId;

	@Column(name = "user_id")
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type")
	private ConversationType type;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	private Conversation(Long userId, ConversationType type) {
		this.publicId = UUID.randomUUID();
		this.userId = userId;
		this.type = type;
		this.createdAt = LocalDateTime.now();
		this.updatedAt = this.createdAt;
	}

	public static Conversation createGuest() {
		return new Conversation(null, ConversationType.GUEST);
	}

	public boolean isGuest() {
		return type == ConversationType.GUEST;
	}

	public void assignToMember(Long userId) {
		this.userId = userId;
		this.type = ConversationType.MEMBER;
		this.updatedAt = LocalDateTime.now();
	}

	public void touch() {
		this.updatedAt = LocalDateTime.now();
	}
}
