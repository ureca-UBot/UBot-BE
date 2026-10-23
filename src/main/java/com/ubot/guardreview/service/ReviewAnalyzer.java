package com.ubot.guardreview.service;

import com.ubot.guardreview.model.ReviewModels.Source;
import com.ubot.guardreview.model.ReviewModels.Suggestion;
import java.util.List;

@FunctionalInterface
public interface ReviewAnalyzer {
	List<Suggestion> analyze(Source source);
}
