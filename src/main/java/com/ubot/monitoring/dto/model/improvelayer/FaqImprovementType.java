package com.ubot.monitoring.dto.model.improvelayer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FaqImprovementType {
	LOW_RAG_QUALITY("검색 품질 낮음"),
	DUPLICATE_SUSPECTED("FAQ 중복 의심"),
	LOW_USAGE("사용도 낮음");

	private final String description;
}
