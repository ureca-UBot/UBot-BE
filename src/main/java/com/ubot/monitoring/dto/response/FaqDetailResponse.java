package com.ubot.monitoring.dto.response;

import com.ubot.monitoring.dto.model.detail.CooccurringFaqItem;
import com.ubot.monitoring.dto.model.detail.FaqBasicInfo;
import com.ubot.monitoring.dto.model.detail.FaqSimilaritySummary;
import com.ubot.monitoring.dto.model.detail.FaqTop1Summary;
import com.ubot.monitoring.dto.model.detail.FaqTopGapSummary;
import com.ubot.monitoring.dto.model.detail.FaqUsageTrend;
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
