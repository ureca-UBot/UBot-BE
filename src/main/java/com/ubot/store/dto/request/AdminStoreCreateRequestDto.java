package com.ubot.store.dto.request;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminStoreCreateRequestDto(
        @NotBlank @Size(max = 150) String storeName,
        @Pattern(regexp = ".*\\S.*") @Size(max = 50) String sido,
        @Pattern(regexp = ".*\\S.*") @Size(max = 50) String sigungu,
        @NotBlank @Size(max = 500) String address,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
        @Pattern(regexp = ".*\\S.*") @Size(max = 30) String phoneNumber,
        @Pattern(regexp = ".*\\S.*") String businessHours,
        @Size(max = 10)
        List<@Valid @Pattern(regexp = "\\s*[A-Z][A-Z0-9_]*\\s*") String> serviceCodes
) {
}
