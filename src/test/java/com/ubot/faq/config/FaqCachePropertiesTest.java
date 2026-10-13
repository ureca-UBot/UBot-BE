package com.ubot.faq.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

class FaqCachePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                            .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });

    @Test
    void existingEnvironmentVariablesBindToTheUnifiedProperties() {
        runner.withPropertyValues(
                "FAQ_CACHE_ENABLED=true", "FAQ_CACHE_SEMANTIC_ENABLED=true",
                "FAQ_CACHE_REGISTRATION_BATCH_SIZE=4", "FAQ_CACHE_MIN_SIMILARITY=0.95",
                "FAQ_CACHE_MIN_MARGIN=0.12")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FaqCacheProperties.class);
                    var properties = context.getBean(FaqCacheProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.isSemanticEnabled()).isTrue();
                    assertThat(properties.getRegistrationBatchSize()).isEqualTo(4);
                    assertThat(properties.getMinSimilarity()).isEqualTo(0.95);
                    assertThat(properties.getMinMargin()).isEqualTo(0.12);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "FAQ_CACHE_REGISTRATION_BATCH_SIZE=0",
            "FAQ_CACHE_MIN_SIMILARITY=1.01",
            "FAQ_CACHE_MIN_MARGIN=-0.01"
    })
    void invalidEnvironmentSettingsStillPreventStartup(String setting) {
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FaqCacheProperties.class)
    static class PropertiesConfiguration {
    }
}
