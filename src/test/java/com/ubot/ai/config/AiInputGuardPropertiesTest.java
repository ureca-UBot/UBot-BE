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

class AiInputGuardPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiRuntimeConfig.class);

    @Test
    void defaultsToEnabledWithoutAnOverride() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiInputGuardProperties.class).isEnabled()).isTrue();
            assertThat(context.getBean(AiInputGuardProperties.class).isLlmEnabled()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bindsTheExplicitInputSwitch(boolean enabled) {
        runner.withPropertyValues("app.ai.input-guard.enabled=" + enabled).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiInputGuardProperties.class).isEnabled()).isEqualTo(enabled);
            assertThat(context.getBean(AiInputGuardProperties.class).isLlmEnabled()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bindsTheLlmSwitchWithoutDisablingCodeInspection(boolean enabled) {
        runner.withPropertyValues("app.ai.input-guard.llm-enabled=" + enabled).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiInputGuardProperties.class).isEnabled()).isTrue();
            assertThat(context.getBean(AiInputGuardProperties.class).isLlmEnabled()).isEqualTo(enabled);
        });
    }

    @Test
    void rejectsAnInvalidValueRatherThanSilentlyDisablingTheGuard() {
        runner.withPropertyValues("app.ai.input-guard.enabled=invalid")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAnInvalidLlmSwitchRatherThanSilentlyDisablingIt() {
        runner.withPropertyValues("app.ai.input-guard.llm-enabled=invalid")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void applicationYamlAlsoDefaultsToEnabled() {
        withApplicationYaml(Map.of()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiInputGuardProperties.class).isEnabled()).isTrue();
            assertThat(context.getBean(AiInputGuardProperties.class).isLlmEnabled()).isTrue();
        });
    }

    @Test
    void applicationYamlAcceptsTheEnvironmentSwitch() {
        withApplicationYaml(Map.of("AI_INPUT_GUARD_ENABLED", "false")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiInputGuardProperties.class).isEnabled()).isFalse();
            assertThat(context.getBean(AiInputGuardProperties.class).isLlmEnabled()).isTrue();
        });
    }

    @Test
    void applicationYamlAcceptsTheSeparateLlmEnvironmentSwitch() {
        withApplicationYaml(Map.of("AI_INPUT_LLM_GUARD_ENABLED", "false")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiInputGuardProperties.class).isEnabled()).isTrue();
            assertThat(context.getBean(AiInputGuardProperties.class).isLlmEnabled()).isFalse();
        });
    }

    @Test
    void disablingInputSafetyDoesNotChangeTheOutputSwitches() {
        withApplicationYaml(Map.of("AI_INPUT_GUARD_ENABLED", "false", "AI_INPUT_LLM_GUARD_ENABLED", "false"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AiInputGuardProperties.class).isEnabled()).isFalse();
                    assertThat(context.getBean(AiInputGuardProperties.class).isLlmEnabled()).isFalse();
                    assertThat(context.getBean(AiOutputGuardProperties.class).isEnabled()).isTrue();
                    assertThat(context.getBean(AiOutputGuardProperties.class).isLlmEnabled()).isTrue();
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
