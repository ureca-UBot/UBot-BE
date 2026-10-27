package com.ubot.guardreview.dto.response;

import com.ubot.guardreview.model.ReviewModels.Run;
import java.time.LocalDateTime;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 상태 조회 응답에는 사례 본문을 포함하지 않고 결과를 JSON 객체로 제공합니다. */
public record ReviewRunResponseDto(long id, String kind, String status, int processed, int total, LocalDateTime cutoff,
		JsonNode result, LocalDateTime createdAt, String analysisMode, boolean detailsRetained) {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	public static ReviewRunResponseDto from(Run summary) {
		return new ReviewRunResponseDto(summary.id(), summary.kind(), summary.status(), summary.processed(),
				summary.total(), summary.cutoff(), JSON.readTree(summary.result()), summary.createdAt(),
				summary.analysisMode(), summary.detailsRetained());
	}
}
