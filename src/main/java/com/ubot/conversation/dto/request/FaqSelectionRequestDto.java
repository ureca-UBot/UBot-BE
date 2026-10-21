package com.ubot.conversation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record FaqSelectionRequestDto(@NotNull UUID selectionId, UUID conversationId, @NotNull @Positive Long faqId) {}
