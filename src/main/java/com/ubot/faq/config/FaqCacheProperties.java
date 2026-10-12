package com.ubot.faq.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "faq-cache")
@Validated
@Getter
@Setter
public class FaqCacheProperties {
    private boolean enabled;
    @NotNull
    private Duration ttl = Duration.ofDays(7);

    @AssertTrue(message = "FAQ cache TTL must be positive")
    public boolean isValidTtl() {
        return ttl != null && ttl.isPositive();
    }
}
