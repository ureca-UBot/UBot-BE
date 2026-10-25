package com.ubot.faq.retrieval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import com.ubot.faq.cache.FaqCacheProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FaqCacheProperties.class)
public class FaqRetrievalConfig {
}
