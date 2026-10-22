package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DuplicateReviewResult {
	DUPLICATE("중복"),
	NEEDS_DISTINCTION("구분 필요"),
	NOT_DUPLICATE("중복 아님"),
	INSUFFICIENT_EVIDENCE("판단 근거 부족");

	private final String description;
}
