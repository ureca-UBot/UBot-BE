package com.ubot.subscription.exception;

import org.springframework.http.HttpStatus;

import com.ubot.common.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SubscriptionErrorCode implements ErrorCode {
	SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "SUB-001", "조회 가능한 회선을 찾을 수 없습니다."),
	SUBSCRIPTION_NOT_ACTIVE(HttpStatus.CONFLICT, "SUB-002", "이용 중인 회선에서만 신청할 수 있습니다."),
	PLAN_CHANGE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "SUB-003", "요금제는 한 달에 한 번만 바꿀 수 있습니다."),
	SAME_PLAN(HttpStatus.CONFLICT, "SUB-004", "이미 이용 중인 요금제입니다."),
	PLAN_AGE_NOT_ELIGIBLE(HttpStatus.BAD_REQUEST, "SUB-005", "나이 조건을 만족하지 않거나 나이를 확인할 수 없어 가입할 수 없는 요금제입니다."),
	ADDON_ALREADY_SUBSCRIBED(HttpStatus.CONFLICT, "SUB-006", "이미 이용 중인 부가서비스입니다."),
	ADDON_NOT_FOUND(HttpStatus.NOT_FOUND, "SUB-007", "이용 중인 부가서비스를 찾을 수 없습니다."),
	INVALID_ROAMING_PERIOD(HttpStatus.BAD_REQUEST, "SUB-008", "로밍 이용 기간이 올바르지 않습니다. 시작일은 오늘 이후, 기간은 최대 90일입니다."),
	ROAMING_NOT_FOUND(HttpStatus.NOT_FOUND, "SUB-009", "취소할 로밍을 찾을 수 없습니다."),
	ROAMING_NOT_CANCELABLE(HttpStatus.CONFLICT, "SUB-010", "사용 시작 전인 로밍만 취소할 수 있습니다."),
	ALREADY_IN_BUNDLE(HttpStatus.CONFLICT, "SUB-011", "이미 결합에 들어가 있는 회선입니다."),
	BUNDLE_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "SUB-012", "이용 중인 결합을 찾을 수 없습니다."),
	BUNDLE_MEMBER_NOT_ADDABLE(HttpStatus.BAD_REQUEST, "SUB-013", "추가할 수 없는 번호입니다."),
	BUNDLE_GROUP_FULL(HttpStatus.CONFLICT, "SUB-014", "이 결합에는 회선을 더 추가할 수 없습니다."),
	BUNDLE_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "SUB-015", "결합에서 뺄 회선을 찾을 수 없습니다."),
	ADDON_INCLUDED_IN_PLAN(HttpStatus.CONFLICT, "SUB-016", "요금제에 포함된 부가서비스는 해지할 수 없습니다. 요금제를 바꾸면 함께 해지됩니다."),
	ROAMING_PERIOD_OVERLAPPED(HttpStatus.CONFLICT, "SUB-017", "같은 기간에 예약하거나 이용 중인 로밍이 있습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
