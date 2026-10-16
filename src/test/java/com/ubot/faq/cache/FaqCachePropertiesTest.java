package com.ubot.faq.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class FaqCachePropertiesTest {
    @Test
    void disabledByDefault() {
        FaqCacheProperties properties = new FaqCacheProperties();
        properties.afterPropertiesSet();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getMinSimilarity()).isEqualTo(0.95);
        assertThat(properties.getMinMargin()).isEqualTo(0.15);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidSimilarity(double value) {
        FaqCacheProperties properties = new FaqCacheProperties();
        properties.setMinSimilarity(value);
        assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -0.01, 2.01, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidMarginIncludingTies(double value) {
        FaqCacheProperties properties = new FaqCacheProperties();
        properties.setMinMargin(value);
        assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bindsSettingsAndRejectsInvalidStartupValues() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CacheConfig.class);
        runner.withPropertyValues("faq.cache.enabled=true", "faq.cache.min-similarity=0.97", "faq.cache.min-margin=0.12")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FaqCacheProperties properties = context.getBean(FaqCacheProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getMinSimilarity()).isEqualTo(0.97);
                    assertThat(properties.getMinMargin()).isEqualTo(0.12);
                });
        runner.withPropertyValues("faq.cache.min-margin=0").run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FaqCacheProperties.class)
    static class CacheConfig {
    }
}
