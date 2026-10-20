package com.ubot.counsel.message.dto.response;

import java.util.UUID;

public record MessageReceiptResponseDto(long messageId, UUID clientMessageId, String status, Long deliverySequence) {}
