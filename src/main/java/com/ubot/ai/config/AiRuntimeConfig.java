package com.ubot.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiRuntimeProperties.class, AiOutputGuardProperties.class, AiInputGuardProperties.class})
public class AiRuntimeConfig {
}
