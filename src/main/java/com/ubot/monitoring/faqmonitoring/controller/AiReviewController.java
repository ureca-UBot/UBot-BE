package com.ubot.monitoring.faqmonitoring.controller;

import com.ubot.common.ApiResponse;
import com.ubot.monitoring.faqmonitoring.dto.request.AiReviewRequest;
import com.ubot.monitoring.faqmonitoring.dto.response.AiReviewResponse;
import com.ubot.monitoring.faqmonitoring.service.AiReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/admin/faq-monitoring/improvements")
public class AiReviewController {
	private final AiReviewService aiReviewService;

	@PostMapping("/{faqId}/ai-review")
	public ApiResponse<AiReviewResponse> review(
			@PathVariable("faqId") @Positive Long faqId,
			@Valid @RequestBody AiReviewRequest request
	) {
		return ApiResponse.success(aiReviewService.review(faqId, request));
	}
}
