package com.ubot.guardreview.exception;

/** 소유권이 불확실한 실행은 결과나 실패를 덮어쓰지 않고 계산을 중단합니다. */
public class ReviewLeaseLostException extends RuntimeException {
	public ReviewLeaseLostException() {
		super("관리자 검토 작업 소유권을 잃었습니다.");
	}
}
