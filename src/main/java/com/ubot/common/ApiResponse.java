package com.ubot.common;

/**
 * API의 성공·실패 응답 형식을 통일하기 위한 공통 응답 DTO입니다.
 *
 * @param success 요청의 처리 성공 여부
 * @param code 응답을 식별하는 애플리케이션 코드
 * @param message 클라이언트에 표시 가능한 메시지
 * @param data 응답 본문. 실패 응답에서는 상세 오류 정보를 담을 수 있습니다.
 */
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data
) {

    public static <T> ApiResponse<T> success(T data) {
        return success("SUCCESS", "요청이 성공적으로 처리되었습니다.", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return success("SUCCESS", message, data);
    }

    public static <T> ApiResponse<T> success(String code, String message, T data) {
        return new ApiResponse<>(true, code, message, data);
    }

    public static ApiResponse<Void> error(String code, String message) {
        return error(code, message, null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return error(errorCode, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, T data) {
        return error(errorCode.getCode(), errorCode.getMessage(), data);
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(false, code, message, data);
    }
}
