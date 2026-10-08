package com.ubot.common.dto.request;

import com.ubot.common.enums.MasterStatus;

import jakarta.validation.constraints.NotNull;

public record ProductStatusUpdateRequestDto(@NotNull MasterStatus status) {
}
