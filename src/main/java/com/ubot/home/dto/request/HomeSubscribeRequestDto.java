package com.ubot.home.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record HomeSubscribeRequestDto(@NotNull @Positive Long homeProductId) {
}
