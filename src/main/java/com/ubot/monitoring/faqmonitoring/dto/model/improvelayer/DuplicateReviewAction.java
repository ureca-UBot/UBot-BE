package com.ubot.monitoring.faqmonitoring.dto.model.improvelayer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DuplicateReviewAction {
	MERGE_CANDIDATE_INTO_TARGET("후보 FAQ를 대상 FAQ에 통합"),
	MERGE_TARGET_INTO_CANDIDATE("대상 FAQ를 후보 FAQ에 통합"),
	CLARIFY_SCOPE("두 FAQ의 질문 범위를 명확히 구분"),
	KEEP_SEPARATE("두 FAQ를 각각 유지"),
	COLLECT_MORE_EVIDENCE("중복 판단 근거를 추가로 확인");

	private final String description;
}
