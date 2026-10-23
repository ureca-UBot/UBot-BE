package com.ubot.guardreview.exception;

/** 모델 호출 여유 부족은 분석 실패나 성공 지문으로 저장하지 않고 다음 주기로 미룹니다. */
public class ReviewAnalysisDeferredException extends RuntimeException {

	public ReviewAnalysisDeferredException() {
		super("모델 호출 여유가 없어 관리자 분석을 다음 주기로 미룹니다.");
	}
}
