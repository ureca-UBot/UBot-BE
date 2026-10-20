package com.ubot.counsel.message.entity;

import com.ubot.counsel.message.dto.response.MessageReceiptResponseDto;
import com.ubot.counsel.message.dto.response.MessageResponseDto;
import java.time.Instant;
import java.util.UUID;

public record Message(long messageId, UUID sessionId, Long senderId, String senderRole,
		UUID clientMessageId, String content, String status, Long deliverySequence,
		long assignmentVersion, Instant createdAt) {
	public MessageReceiptResponseDto receipt() {
		return new MessageReceiptResponseDto(messageId, clientMessageId, status, deliverySequence);
	}
	public MessageResponseDto delivery() {
		return new MessageResponseDto(messageId, senderId, senderRole, content, deliverySequence, createdAt);
	}
}
