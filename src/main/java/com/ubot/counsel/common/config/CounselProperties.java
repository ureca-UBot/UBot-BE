package com.ubot.counsel.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("counsel")
public record CounselProperties(boolean enabled,
		@Min(10) int heartbeatSeconds, @Min(30) int reconnectSeconds,
		@Min(15) int pendingTimeoutSeconds,
		@Min(1) int rateWindowSeconds, @Min(1) int rateMaxRequests,
		@Valid Guard guard) {
	public record Guard(@NotBlank String embeddingUrl, @NotBlank String model, @NotBlank String profile,
			@DecimalMin("0.8") @DecimalMax("1.0") double threshold,
			@Min(1) @Max(10) int topK, @Min(1) @Max(2) int concurrency, @Min(100) @Max(2000) int connectTimeoutMs,
			@Min(100) @Max(5000) int responseTimeoutMs) {}
}
