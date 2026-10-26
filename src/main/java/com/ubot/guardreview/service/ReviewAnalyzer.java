package com.ubot.guardreview.service;

import com.ubot.guardreview.model.ReviewModels.Source;
import com.ubot.guardreview.model.ReviewModels.Suggestion;
import java.util.List;

@FunctionalInterface
public interface ReviewAnalyzer {
	List<Suggestion> analyze(Source source);
	default com.ubot.guardreview.model.ReviewReassessment reassess(Source source) {
		throw new UnsupportedOperationException("보류 재검토를 지원하지 않는 분석기입니다.");
	}
}
