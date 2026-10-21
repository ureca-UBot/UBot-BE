package com.ubot.counsel.admin.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SafetySentenceIndexRequestDto(@NotNull @Min(1) Long revision) {}
