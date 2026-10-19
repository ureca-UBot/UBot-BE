package com.ubot.monitoring.operationmonitoring;

import com.ubot.common.GlobalException;
import com.ubot.common.exception.CommonErrorCode;
import java.time.Duration;
import java.util.Arrays;

/** 관리자 서비스 모니터링에서 허용하는 조회 기간과 반환 해상도입니다. */
public enum ServiceMonitoringRange {
    FIFTEEN_MINUTES("15m", Duration.ofMinutes(15), Duration.ofSeconds(15)),
    ONE_HOUR("1h", Duration.ofHours(1), Duration.ofSeconds(30)),
    SIX_HOURS("6h", Duration.ofHours(6), Duration.ofMinutes(1)),
    TWENTY_FOUR_HOURS("24h", Duration.ofHours(24), Duration.ofMinutes(5));

    private final String value;
    private final Duration duration;
    private final Duration step;

    ServiceMonitoringRange(String value, Duration duration, Duration step) {
        this.value = value;
        this.duration = duration;
        this.step = step;
    }

    public static ServiceMonitoringRange from(String value) {
        return Arrays.stream(values())
                .filter(range -> range.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new GlobalException(
                        CommonErrorCode.INVALID_PARAMETER,
                        "range는 15m, 1h, 6h, 24h 중 하나여야 합니다."
                ));
    }

    public String value() {
        return value;
    }

    public Duration duration() {
        return duration;
    }

    public Duration step() {
        return step;
    }
}
