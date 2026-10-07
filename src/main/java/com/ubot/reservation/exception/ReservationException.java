package com.ubot.reservation.exception;

import com.ubot.common.GlobalException;

public class ReservationException extends GlobalException {
	public ReservationException(ReservationErrorCode errorCode){
		super(errorCode);
	}
}
