package com.ubot.counsel.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SafetyEvaluationRequestDto(@NotBlank @Size(max=1000) String content) {}
