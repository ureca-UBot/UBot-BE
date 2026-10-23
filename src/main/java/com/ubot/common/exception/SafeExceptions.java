package com.ubot.common.exception;

import org.springframework.core.NestedExceptionUtils;

// 예외 메시지에는 질문·SQL·응답 본문이 들어갈 수 있어 로그에는 예외 종류와 호출 위치만 남김
public final class SafeExceptions {
	private static final int MAX_CAUSES = 16;

	private SafeExceptions() {}

	// "예외/가장 안쪽 원인" 형식. 스택트레이스가 필요 없는 WARN 로그에 씀
	public static String type(Throwable exception) {
		return exception.getClass().getSimpleName() + "/"
				+ NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName();
	}

	// 메시지를 지운 사본. 원인 연결과 스택트레이스는 유지해 ERROR 로그에서 실패 위치를 찾을 수 있음
	public static Throwable withoutMessages(Throwable exception) {
		return mask(exception, 0);
	}

	private static Throwable mask(Throwable exception, int depth) {
		Throwable cause = exception.getCause();
		boolean keepCause = cause != null && cause != exception && depth < MAX_CAUSES;
		MaskedException masked = new MaskedException(exception.getClass().getName(), keepCause ? mask(cause, depth + 1) : null);
		masked.setStackTrace(exception.getStackTrace());
		return masked;
	}

	// 메시지로 원래 예외 클래스 이름만 가짐
	static final class MaskedException extends RuntimeException {
		private MaskedException(String originalType, Throwable cause) {
			super(originalType + " (메시지 생략)", cause, false, true);
		}
	}
}
