package com.ubot.monitoring.faqmonitoring.dto.response;

import com.ubot.monitoring.faqmonitoring.dto.model.detail.CooccurringFaqItem;
import com.ubot.monitoring.faqmonitoring.dto.model.detail.FaqBasicInfo;
import com.ubot.monitoring.faqmonitoring.dto.model.detail.FaqSimilaritySummary;
import com.ubot.monitoring.faqmonitoring.dto.model.detail.FaqTop1Summary;
import com.ubot.monitoring.faqmonitoring.dto.model.detail.FaqTopGapSummary;
import com.ubot.monitoring.faqmonitoring.dto.model.detail.FaqUsageTrend;
import java.util.List;

public record FaqDetailResponse(
		FaqBasicInfo basicInfo,
		FaqUsageTrend usageTrend,
		FaqSimilaritySummary similarity,
		FaqTop1Summary top1,
		FaqTopGapSummary topGap,
		List<CooccurringFaqItem> cooccurringFaqs
) {
}
