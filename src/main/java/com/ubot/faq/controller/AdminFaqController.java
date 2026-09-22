package com.ubot.faq.controller;

import com.ubot.auth.config.CustomUserDetails;
import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.faq.dto.request.FaqCategoryCreateRequestDto;
import com.ubot.faq.dto.request.FaqCategoryUpdateRequestDto;
import com.ubot.faq.dto.request.FaqCreateRequestDto;
import com.ubot.faq.dto.request.FaqUpdateRequestDto;
import com.ubot.faq.dto.response.FaqCategoryResponseDto;
import com.ubot.faq.dto.response.FaqLogResponseDto;
import com.ubot.faq.dto.response.FaqResponseDto;
import com.ubot.faq.dto.response.OldFaqResponseDto;
import com.ubot.faq.service.FaqCategoryService;
import com.ubot.faq.service.FaqLogService;
import com.ubot.faq.service.FaqService;
import com.ubot.faq.service.OldFaqService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Validated
public class AdminFaqController {
	private final FaqService faqService;
	private final FaqCategoryService faqCategoryService;
	private final FaqLogService faqLogService;
	private final OldFaqService oldFaqService;

//	FaqService 관련 컨트롤러들

	@GetMapping("/faqs")
	public ApiResponse<PageResponseDto<FaqResponseDto>> getFaqList(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) Long categoryId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
	){
		return ApiResponse.success(faqService.getActiveFaqList(page, size, keyword, categoryId));
	}

	@GetMapping("/deleted-faqs")
	public ApiResponse<PageResponseDto<FaqResponseDto>> getDeletedFaqList(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
	){
		return ApiResponse.success(faqService.getDeletedFaqList(page, size));
	}

	@GetMapping("/faqs/{faqId}")
	public ApiResponse<FaqResponseDto> getFaqById(
			@Positive @PathVariable("faqId") Long faqId
	){
		return ApiResponse.success(faqService.getActiveFaq(faqId));
	}

	@PostMapping("/faqs")
	public ApiResponse<FaqResponseDto> createFaq(
			@Valid @RequestBody FaqCreateRequestDto requestDto,
			@AuthenticationPrincipal CustomUserDetails userDetails){
		Long userId = userDetails.getUserId();
		return ApiResponse.success(faqService.createFaq(requestDto, userId));
	}

	@PatchMapping("/faqs")
	public ApiResponse<FaqResponseDto> updateFaq(
			@Valid @RequestBody FaqUpdateRequestDto requestDto,
			@AuthenticationPrincipal CustomUserDetails userDetails){
		Long userId = userDetails.getUserId();
		return ApiResponse.success(faqService.updateActiveFaq(requestDto, userId));
	}

	@DeleteMapping("/faqs/{faqId}")
	public ApiResponse<Void>  deleteFaq(
			@Positive @PathVariable("faqId") Long faqId
	){
		faqService.deleteFaq(faqId);
		return ApiResponse.success("해당 FAQ가 삭제되었습니다.", null);
	}

//	FaqCategory 관련 컨트롤러들

	@GetMapping("/faq-categories/{faqCategoryId}")
	public ApiResponse<FaqCategoryResponseDto> getFaqCategoryById(
			@Positive @PathVariable("faqCategoryId") Long faqCategoryId
	){
		return ApiResponse.success(faqCategoryService.getFaqCategory(faqCategoryId));
	}

	@GetMapping("/faq-categories/{faqCategoryId}/faqs")
	public ApiResponse<PageResponseDto<FaqResponseDto>> getFaqListByFaqCategoryId(
			@Positive @PathVariable("faqCategoryId") Long faqCategoryId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
	){
		return ApiResponse.success(faqService.getFaqListByFaqCategoryId(page, size, faqCategoryId));
	}

	@GetMapping("/faq-categories")
	public ApiResponse<PageResponseDto<FaqCategoryResponseDto>> getFaqCategoryList(
			@RequestParam(required = false) String keyword,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
	){
		PageResponseDto<FaqCategoryResponseDto> result;

		if(StringUtils.hasText(keyword))
			result = faqCategoryService.searchFaqCategories(page, size, keyword);
		else
			result = faqCategoryService.getFaqCategories(page, size);

		return ApiResponse.success(result);
	}

	@PostMapping("/faq-categories")
	public ApiResponse<FaqCategoryResponseDto> createFaqCategory(
			@Valid @RequestBody FaqCategoryCreateRequestDto requestDto
	){
		return ApiResponse.success(faqCategoryService.createFaqCategory(requestDto));
	}

	@PatchMapping("/faq-categories/{faqCategoryId}")
	public ApiResponse<FaqCategoryResponseDto> updateFaqCategory(
			@Positive @PathVariable("faqCategoryId") Long faqCategoryId,
			@Valid @RequestBody FaqCategoryUpdateRequestDto requestDto
	){
		return ApiResponse.success(faqCategoryService.updateFaqCategory(faqCategoryId, requestDto));
	}


	@DeleteMapping("/faq-categories/{faqCategoryId}")
	public ApiResponse<Void>  deleteFaqCategory(
			@Positive @PathVariable("faqCategoryId") Long faqCategoryId
	){
		faqCategoryService.deleteFaqCategory(faqCategoryId);
		return ApiResponse.success("해당 카테고리가 삭제되었습니다.", null);
	}

//	FaqLog 관련 컨트롤러들

	@GetMapping("/faq-logs/question-log")
	public ApiResponse<PageResponseDto<FaqLogResponseDto>>getFaqLogsByQuestionLogId(
			@Positive @RequestParam Long questionLogId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
	){
		return ApiResponse.success(faqLogService.getFaqLogsByQuestionLogId(page, size, questionLogId));
	}

	@GetMapping("/faq-logs/faq")
	public ApiResponse<PageResponseDto<FaqLogResponseDto>>getFaqLogsByFaqId(
			@Positive @RequestParam Long faqId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
	){
		return ApiResponse.success(faqLogService.getFaqLogsByFaqId(page, size, faqId));
	}

//	OldFaq 관련 컨트롤러들

	@GetMapping("/old-faqs/faq")
	public ApiResponse<PageResponseDto<OldFaqResponseDto>>getOldFaqByFaqId(
			@Positive @RequestParam Long faqId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
	){
		return ApiResponse.success(oldFaqService.getOldFaqByFaqId(page, size, faqId));
	}

	@GetMapping("/old-faqs/faq-category")
	public ApiResponse<PageResponseDto<OldFaqResponseDto>>getOldFaqByFaqCategoryId(
			@Positive @RequestParam Long faqCategoryId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
	){
		return ApiResponse.success(oldFaqService.getOldFaqByFaqCategoryId(page, size, faqCategoryId));
	}
}