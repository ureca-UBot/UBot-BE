package com.ubot.conversation.dto.response;

import java.util.UUID;

public record FaqSelectionResponseDto(UUID selectionId, UUID conversationId, Long faqId, String question, String answer) {}
