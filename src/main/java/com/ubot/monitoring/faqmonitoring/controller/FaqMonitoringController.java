package com.ubot.monitoring.faqmonitoring.controller;

import com.ubot.common.ApiResponse;
import com.ubot.monitoring.faqmonitoring.dto.model.detail.FaqUsageTrendPeriod;
import com.ubot.monitoring.faqmonitoring.dto.response.FaqDetailResponse;
import com.ubot.monitoring.faqmonitoring.dto.response.FaqImprovementResponse;
import com.ubot.monitoring.faqmonitoring.dto.response.FaqMonitoringResponse;
import com.ubot.monitoring.faqmonitoring.service.FaqMonitoringService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/faq-monitoring")
public class FaqMonitoringController {
	private final FaqMonitoringService faqMonitoringService;

	@GetMapping
	public ApiResponse<FaqMonitoringResponse> getMonitoringData(
			@RequestParam("startAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
			@RequestParam("endAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt
	) {
		return ApiResponse.success(faqMonitoringService.getMonitoringData(startAt, endAt));
	}

	@GetMapping("/faqs/{faqId}")
	public ApiResponse<FaqDetailResponse> getFaqDetail(
			@RequestParam(name = "period", defaultValue = "MONTH") FaqUsageTrendPeriod period,
			@RequestParam(name = "asOf", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime asOf,
			@PathVariable("faqId") Long faqId
	) {
		return ApiResponse.success(faqMonitoringService.getFaqDetail(faqId, period, asOf));
	}

	@GetMapping("/improvements")
	public ApiResponse<FaqImprovementResponse> getFaqImprovements() {
		return ApiResponse.success(faqMonitoringService.getFaqImprovements());
	}
}
