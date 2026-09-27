package com.ubot.store.dto.request;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminStoreUpdateRequestDto(
        @Pattern(regexp = ".*\\S.*") @Size(max = 150) String storeName,
        @Pattern(regexp = ".*\\S.*") @Size(max = 50) String sido,
        @Pattern(regexp = ".*\\S.*") @Size(max = 50) String sigungu,
        @Pattern(regexp = ".*\\S.*") @Size(max = 500) String address,
        @DecimalMin("33.0") @DecimalMax("39.0") BigDecimal latitude,
        @DecimalMin("124.0") @DecimalMax("132.0") BigDecimal longitude,
        @Pattern(regexp = ".*\\S.*") @Size(max = 30) String phoneNumber,
        @Pattern(regexp = ".*\\S.*") String businessHours,
        @Size(max = 10)
        List<@NotBlank @Pattern(regexp = "\\s*[A-Z][A-Z0-9_]*\\s*") String> serviceCodes
) {
}
