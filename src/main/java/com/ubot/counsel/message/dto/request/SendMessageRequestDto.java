package com.ubot.counsel.message.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SendMessageRequestDto(@NotNull UUID clientMessageId, @NotBlank @Size(max=1000) String content) {}
