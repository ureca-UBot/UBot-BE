package com.ubot.faq.publicapi;

import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 비로그인 공개 FAQ 조회입니다. SecurityConfig와 JwtAuthenticationFilter에서 토큰 없이 허용합니다. */
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/public/faqs")
public class PublicFaqController {
	private final PublicFaqService service;

	@GetMapping("/categories")
	public ApiResponse<List<PublicFaqResponse.Category>> getCategories() {
		return ApiResponse.success(service.categories());
	}

	/** size는 10·20·50만 허용합니다(PublicFaqService). */
	@GetMapping
	public ApiResponse<PageResponseDto<PublicFaqResponse.Summary>> getFaqs(
			@RequestParam(required = false) String category,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") int size) {
		return ApiResponse.success(service.list(category, page, size));
	}

	@GetMapping("/{id}")
	public ApiResponse<PublicFaqResponse.Detail> getFaq(@PathVariable @Positive Long id) {
		return ApiResponse.success(service.detail(id));
	}
}
