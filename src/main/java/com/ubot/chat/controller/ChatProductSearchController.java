package com.ubot.chat.controller;

import com.ubot.ai.tool.product.ProductRefine;
import com.ubot.ai.tool.product.ProductRefineSearch;
import com.ubot.chat.dto.request.ProductSearchOptionsRequestDto;
import com.ubot.chat.dto.request.ProductSearchRequestDto;
import com.ubot.chat.dto.response.ProductSearchResponseDto;
import com.ubot.chat.exception.ChatErrorCode;
import com.ubot.chat.exception.ChatException;
import com.ubot.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 상품 목록 답변의 조건 선택지에서 고른 값으로 LLM 없이 바로 다시 조회합니다. 공개 상품 정보라 비회원도 쓸 수 있습니다.
// 답변 작업(job)을 만들지 않아 결과는 대화 기록에 남지 않습니다.
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/chat")
public class ChatProductSearchController {

	private final ProductRefineSearch productRefineSearch;

	@PostMapping("/product-search")
	public ApiResponse<ProductSearchResponseDto> search(@Valid @RequestBody ProductSearchRequestDto request) {
		try {
			ProductRefineSearch.Result result = productRefineSearch.search(
					request.type(), request.keyword(), request.conditions());
			return ApiResponse.success(new ProductSearchResponseDto(result.answer(), result.productData()));
		} catch (ProductRefineSearch.InvalidConditionException exception) {
			log.info("버튼 조건 상품 조회 요청이 올바르지 않습니다: type={}, 사유={}", request.type(), exception.getMessage());
			throw new ChatException(ChatErrorCode.INVALID_PRODUCT_SEARCH);
		} catch (ProductRefineSearch.LookupFailedException exception) {
			throw new ChatException(ChatErrorCode.PRODUCT_SEARCH_FAILED);
		}
	}

	// 단계별 조건 선택에서 그 단계의 선택지마다 결과가 있는지 돌려줍니다. 화면은 결과가 없는 선택지를 고를 수 없게 합니다.
	@PostMapping("/product-search/options")
	public ApiResponse<ProductRefine.Availability> options(@Valid @RequestBody ProductSearchOptionsRequestDto request) {
		try {
			return ApiResponse.success(productRefineSearch.options(
					request.type(), request.keyword(), request.conditions(), request.key()));
		} catch (ProductRefineSearch.InvalidConditionException exception) {
			log.info("버튼 조건 단계 선택지 요청이 올바르지 않습니다: type={}, key={}, 사유={}", request.type(), request.key(),
					exception.getMessage());
			throw new ChatException(ChatErrorCode.INVALID_PRODUCT_SEARCH);
		} catch (ProductRefineSearch.LookupFailedException exception) {
			throw new ChatException(ChatErrorCode.PRODUCT_SEARCH_FAILED);
		}
	}
}
