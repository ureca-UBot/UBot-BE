package com.ubot.monitoring.faqmonitoring.dto.model.detail;

public record FaqTop1Summary(
		long top1Count,
		double top1Rate,
		double overallAverageTop1Count
) {
}
