package com.ubot.monitoring.controller;

import com.ubot.monitoring.dto.response.FaqDetailResponse;
import com.ubot.monitoring.dto.response.FaqMonitoringResponse;
import com.ubot.monitoring.dto.model.detail.FaqUsageTrendPeriod;
import com.ubot.monitoring.service.FaqMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/faq-monitoring")
public class FaqMonitoringController {
	private final FaqMonitoringService faqMonitoringService;

	@GetMapping
	public FaqMonitoringResponse getMonitoringData(
			@RequestParam("startAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
			@RequestParam("endAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt
			){
		return faqMonitoringService.getMonitoringData(startAt, endAt);
	}

	@GetMapping("/faqs/{faqId}")
	public FaqDetailResponse getFaqDetail(
			@RequestParam(name = "period", defaultValue = "MONTH") FaqUsageTrendPeriod period,
			@RequestParam(name = "asOf", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime asOf,
			@PathVariable("faqId") Long faqId
	){
		return faqMonitoringService.getFaqDetail(faqId, period, asOf);
	}
}
