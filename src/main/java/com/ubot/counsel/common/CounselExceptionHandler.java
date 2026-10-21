package com.ubot.counsel.common;

import com.ubot.common.ApiResponse;
import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;
import com.ubot.counsel.common.exception.CounselErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// SQL 예외에 메시지 원문이 포함될 수 있으므로 코드와 예외 종류만 기록함
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.ubot.counsel")
public class CounselExceptionHandler {
	@ExceptionHandler(GlobalException.class)
	ResponseEntity<ApiResponse<Void>> handleGlobalException(GlobalException e) {
		if (e.getErrorCode().getStatus().is5xxServerError()) logFailure(e.getErrorCode(), e);
		return ResponseEntity.status(e.getErrorCode().getStatus())
				.body(ApiResponse.error(e.getErrorCode().getCode(), e.getMessage()));
	}
	@ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
	ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception ignored) { return error(CounselErrorCode.INVALID_INPUT); }
	@ExceptionHandler(DataAccessException.class)
	ResponseEntity<ApiResponse<Void>> handleDatabaseException(DataAccessException exception) {
		logFailure(CounselErrorCode.DATABASE_UNAVAILABLE, exception);
		return error(CounselErrorCode.DATABASE_UNAVAILABLE);
	}
	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
		logFailure(CounselErrorCode.INTERNAL_ERROR, exception);
		return error(CounselErrorCode.INTERNAL_ERROR);
	}
	private void logFailure(ErrorCode code, Exception exception) {
		log.error("상담 요청 실패 code={} exception={}", code.getCode(), exception.getClass().getSimpleName());
	}
	private ResponseEntity<ApiResponse<Void>> error(ErrorCode code) {
		return ResponseEntity.status(code.getStatus()).body(ApiResponse.error(code));
	}
}
