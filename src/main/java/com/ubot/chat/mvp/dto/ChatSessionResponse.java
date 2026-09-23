package com.ubot.chat.mvp.dto;

import java.time.LocalDateTime;

/** A persistent grouping of questions, not LLM conversation memory. */
public record ChatSessionResponse(long sessionId, LocalDateTime createdAt) {}
