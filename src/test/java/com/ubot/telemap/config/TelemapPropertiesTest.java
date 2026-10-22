package com.ubot.telemap.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.telemap.exception.TelemapErrorCode;
import com.ubot.telemap.exception.TelemapException;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("통신 지도 설정 검증")
class TelemapPropertiesTest {

    @Test
    @DisplayName("기본 설정은 유효하다")
    void acceptsDefaults() {
        assertThatCode(new TelemapProperties()::afterPropertiesSet).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSettings")
    @DisplayName("허용 범위를 벗어난 설정을 거부한다")
    void rejectsInvalidSetting(String description, Consumer<TelemapProperties> invalidator) {
        TelemapProperties properties = new TelemapProperties();
        invalidator.accept(properties);

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOfSatisfying(TelemapException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(TelemapErrorCode.INVALID_CONFIGURATION));
    }

    static Object[][] invalidSettings() {
        return new Object[][] {
                {"시간 창이 0", (Consumer<TelemapProperties>) value -> value.setWindowMinutes(0)},
                {"건수 임계값이 0", (Consumer<TelemapProperties>) value -> value.setCountThreshold(0)},
                {"유사도 임계값이 0", (Consumer<TelemapProperties>) value -> value.setClusterThreshold(0)},
                {"유사도 임계값이 1 초과", (Consumer<TelemapProperties>) value -> value.setClusterThreshold(1.01)},
                {"보존 시간이 시간 창보다 짧음", (Consumer<TelemapProperties>) value -> {
                    value.setWindowMinutes(10);
                    value.setRetentionMinutes(9);
                }},
                {"제목 생성 질문 수가 0", (Consumer<TelemapProperties>) value -> value.setMaxQuestionsForSubject(0)}
        };
    }
}
