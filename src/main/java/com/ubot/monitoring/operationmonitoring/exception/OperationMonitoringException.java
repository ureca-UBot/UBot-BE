package com.ubot.monitoring.operationmonitoring.exception;

import com.ubot.common.GlobalException;

public class OperationMonitoringException extends GlobalException {
    public OperationMonitoringException(OperationMonitoringErrorCode errorCode) {
        super(errorCode);
    }
}
