package com.ubot.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatRateLimitProperties.class)
public class ChatRateLimitConfig {
}
