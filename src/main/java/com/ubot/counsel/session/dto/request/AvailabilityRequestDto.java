package com.ubot.counsel.session.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AvailabilityRequestDto(@NotBlank String status) {}
