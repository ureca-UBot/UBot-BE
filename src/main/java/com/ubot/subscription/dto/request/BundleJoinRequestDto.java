package com.ubot.subscription.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record BundleJoinRequestDto(@NotNull @Positive Long bundleProductId) {
}
