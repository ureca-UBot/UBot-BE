package com.ubot.guardreview.model;

import com.ubot.guardreview.model.ReviewModels.Source;
import com.ubot.guardreview.model.ReviewModels.Suggestion;
import java.util.List;

/** 관리자 재검토 의견입니다. 실시간 가드 판정이나 사용자 응답을 변경하지 않습니다. */
public record ReviewReassessment(Verdict verdict, String reason, List<Suggestion> suggestions) {

	public enum Verdict { NORMAL_POSSIBLE, ISSUE_SUSPECTED, INSUFFICIENT_CONTEXT }

	public void validate(Source source) {
		if (!"REVIEW".equals(source.outcome())
				|| !List.of("INPUT_LLM", "OUTPUT_LLM").contains(source.origin())
				|| verdict == null || reason == null || reason.isBlank() || reason.length() > 2000
				|| suggestions == null || suggestions.size() > 20
				|| (verdict == Verdict.ISSUE_SUSPECTED) == suggestions.isEmpty())
			throw new IllegalArgumentException("보류 재검토 결과를 확인해 주세요.");
	}
}
