package com.ubot.monitoring.operationmonitoring.exception;

import com.ubot.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OperationMonitoringErrorCode implements ErrorCode {
    PROMETHEUS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MON-001", "모니터링 수집 시스템에 연결할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
