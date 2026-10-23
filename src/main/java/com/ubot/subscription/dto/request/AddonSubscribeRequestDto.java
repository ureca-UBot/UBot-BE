package com.ubot.subscription.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddonSubscribeRequestDto(@NotNull @Positive Long addonServiceId) {
}
