package com.ubot.chat.service;

/**
 * Kiwi 형태소 분석이 실패했을 때 QuestionMorphology가 던집니다. Kiwi 네이티브 코드는 선언하지 않은 checked 예외
 * (java.lang.Exception)도 던질 수 있어, 네이티브 호출 바로 옆에서 이 예외 하나로 바꿉니다.
 * 질문 정리 단계(경로 분류·가드·멀티턴 판정)는 RuntimeException으로 이 예외까지 잡아 원문으로 진행합니다.
 */
class MorphologyException extends RuntimeException {

	MorphologyException(Throwable cause) {
		super("형태소 분석에 실패했습니다: " + cause, cause);
	}
}
