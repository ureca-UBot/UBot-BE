package com.ubot.faq.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties("faq-semantic-cache")
@Validated
@Getter
@Setter
public class FaqSemanticCacheProperties {
    private boolean enabled;
    private boolean reviewedPolicyEnabled;
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minSimilarity = 0.95;
    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private double minMargin = 0.15;
    @NotBlank
    private String embeddingModel = "bge-m3:567m";
}
