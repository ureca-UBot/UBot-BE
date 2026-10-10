package com.ubot.monitoring.controller;

import com.ubot.monitoring.dto.response.FaqDetailResponse;
import com.ubot.monitoring.dto.response.FaqMonitoringResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/faq-monitoring")
public class FaqMonitoringController {

	@GetMapping
	public FaqMonitoringResponse getMonitoringData(
			@RequestParam("startAt") LocalDateTime startAt,
			@RequestParam("endAt") LocalDateTime endAt
			){
		return null;
	}

	@GetMapping("/faqs/{faqId}")
	public FaqDetailResponse getFaqDetail(
			@RequestParam("startAt") LocalDateTime startAt,
			@RequestParam("endAt") LocalDateTime endAt,
			@PathVariable("faqId") Long faqId
	){
		return null;
	}
}
