package com.ubot.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

class AiOutputGuardPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiRuntimeConfig.class);

    @Test
    void defaultsToEnabledWithoutAnOverride() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiOutputGuardProperties.class).isEnabled()).isTrue();
            assertThat(context.getBean(AiOutputGuardProperties.class).isLlmEnabled()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bindsTheExplicitOutputSwitch(boolean enabled) {
        runner.withPropertyValues("app.ai.output-guard.enabled=" + enabled).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiOutputGuardProperties.class).isEnabled()).isEqualTo(enabled);
            assertThat(context.getBean(AiOutputGuardProperties.class).isLlmEnabled()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bindsTheLlmSwitchWithoutDisablingCodeInspection(boolean enabled) {
        runner.withPropertyValues("app.ai.output-guard.llm-enabled=" + enabled).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiOutputGuardProperties.class).isEnabled()).isTrue();
            assertThat(context.getBean(AiOutputGuardProperties.class).isLlmEnabled()).isEqualTo(enabled);
        });
    }

    @Test
    void rejectsAnInvalidValueRatherThanSilentlyDisablingTheGuard() {
        runner.withPropertyValues("app.ai.output-guard.enabled=invalid")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAnInvalidLlmSwitchRatherThanSilentlyDisablingIt() {
        runner.withPropertyValues("app.ai.output-guard.llm-enabled=invalid")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void applicationYamlAlsoDefaultsToEnabled() {
        withApplicationYaml(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiOutputGuardProperties.class).isEnabled()).isTrue();
            assertThat(context.getBean(AiOutputGuardProperties.class).isLlmEnabled()).isTrue();
        });
    }

    @Test
    void applicationYamlAcceptsTheEnvironmentSwitch() {
        withApplicationYaml(Map.of("AI_OUTPUT_GUARD_ENABLED", "false")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiOutputGuardProperties.class).isEnabled()).isFalse();
            assertThat(context.getBean(AiOutputGuardProperties.class).isLlmEnabled()).isTrue();
        });
    }

    @Test
    void applicationYamlAcceptsTheSeparateLlmEnvironmentSwitch() {
        withApplicationYaml(Map.of("AI_OUTPUT_LLM_GUARD_ENABLED", "false")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiOutputGuardProperties.class).isEnabled()).isTrue();
            assertThat(context.getBean(AiOutputGuardProperties.class).isLlmEnabled()).isFalse();
        });
    }

    private ApplicationContextRunner withApplicationYaml(Map<String, Object> environment) {
        return runner.withPropertyValues("app.ai.mode=ollama").withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            // 실제 환경변수나 서버 설정을 바꾸지 않고 운영 YAML의 기본값·환경변수 연결을 검사합니다.
            sources.replace("systemEnvironment", new SystemEnvironmentPropertySource("systemEnvironment", environment));
            try {
                for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
                    sources.addLast(source);
                }
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }
}
