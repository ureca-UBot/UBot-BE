package com.ubot.monitoring.faqmonitoring.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiReviewProperties.class)
public class AiReviewConfig {
}
