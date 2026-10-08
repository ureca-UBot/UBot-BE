package com.ubot.plan.exception;

import com.ubot.common.GlobalException;

public class PlanException extends GlobalException {

    public PlanException(PlanErrorCode errorCode) {
        super(errorCode);
    }
}
