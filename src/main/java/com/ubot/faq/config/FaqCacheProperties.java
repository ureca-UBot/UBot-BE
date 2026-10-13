package com.ubot.faq.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "faq-cache")
@Getter
@Setter
public class FaqCacheProperties {
    private boolean enabled;
}
