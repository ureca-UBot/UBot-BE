package com.ubot.monitoring.operationmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.common.GlobalException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ServiceMonitoringRangeTest {

    @ParameterizedTest
    @MethodSource("supportedRanges")
    void supportsOnlyConfiguredRanges(String value, ServiceMonitoringRange expected) {
        assertThat(ServiceMonitoringRange.from(value)).isEqualTo(expected);
    }

    static Stream<Arguments> supportedRanges() {
        return Stream.of(
                Arguments.of("15m", ServiceMonitoringRange.FIFTEEN_MINUTES),
                Arguments.of("1h", ServiceMonitoringRange.ONE_HOUR),
                Arguments.of("6h", ServiceMonitoringRange.SIX_HOURS),
                Arguments.of("24h", ServiceMonitoringRange.TWENTY_FOUR_HOURS)
        );
    }

    @ParameterizedTest
    @MethodSource("unsupportedRangeValues")
    void rejectsUnsupportedRanges(String value) {
        assertThatThrownBy(() -> ServiceMonitoringRange.from(value))
                .isInstanceOf(GlobalException.class);
    }

    static Stream<String> unsupportedRangeValues() {
        return Stream.of("5m", "7d", "6H", "");
    }
}
