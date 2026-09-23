package com.ubot.faq.service;

import com.ubot.common.PageResponseDto;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.faq.dto.request.FaqCategoryCreateRequestDto;
import com.ubot.faq.dto.request.FaqCategoryUpdateRequestDto;
import com.ubot.faq.dto.response.FaqCategoryResponseDto;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.repository.FaqCategoryRepository;
import com.ubot.faq.repository.FaqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class FaqCategoryService {
	private final FaqCategoryRepository faqCategoryRepository;
	private final FaqRepository faqRepository;

	@Transactional
	public FaqCategoryResponseDto createFaqCategory(FaqCategoryCreateRequestDto requestDto) {
		String categoryName = requestDto.name();
		if(faqCategoryRepository.findByNameAndDeletedAtIsNull(categoryName).isPresent()) {
			throw new FaqException(FaqErrorCode.FAQ_CATEGORY_EXIST, categoryName + "는 이미 존재하는 카테고리명입니다.");
		}

		FaqCategory faqCategory = FaqCategory.builder()
				.name(categoryName)
				.createdAt(LocalDateTime.now())
				.updatedAt(LocalDateTime.now())
				.build();

		return FaqCategoryResponseDto.from(faqCategoryRepository.save(faqCategory));
	}

	public FaqCategoryResponseDto getFaqCategory(Long faqCategoryId) {
		FaqCategory faqCategory = faqCategoryRepository.findByIdAndDeletedAtIsNull(faqCategoryId).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_CATEGORY_NOT_FOUND));
		return  FaqCategoryResponseDto.from(faqCategory);
	}

	public PageResponseDto<FaqCategoryResponseDto> getFaqCategories(int page, int size, String keyword) {
		String normalizedKeyword = StringUtils.hasText(keyword) ? keyword : "";

		Page<FaqCategoryResponseDto> faqCategoryPage = faqCategoryRepository
				.findByDeletedAtIsNull(PageRequest.of(page, size, Sort.by(
						Sort.Order.desc("createdAt"),
						Sort.Order.desc("id")
				)), normalizedKeyword)
				.map(FaqCategoryResponseDto::from);

		return PageResponseDto.from(faqCategoryPage);
	}

	@Transactional
	public FaqCategoryResponseDto updateFaqCategory(Long faqCategoryId, FaqCategoryUpdateRequestDto requestDto) {
		FaqCategory faqCategory = faqCategoryRepository.findByIdAndDeletedAtIsNull(faqCategoryId).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_CATEGORY_NOT_FOUND));
		String beforeCategoryName = faqCategory.getName();
		String afterCategoryName = requestDto.afterName();

		if(beforeCategoryName.equals(afterCategoryName)) {
			throw new FaqException(FaqErrorCode.FAQ_CATEGORY_SAME_NAME);
		}

		if(faqCategoryRepository.findByNameAndDeletedAtIsNull(afterCategoryName).isPresent()) {
			throw new FaqException(FaqErrorCode.FAQ_CATEGORY_EXIST, afterCategoryName + "는 이미 존재하는 카테고리명입니다.");
		}

		faqCategory.update(afterCategoryName);

		return FaqCategoryResponseDto.from(faqCategoryRepository.save(faqCategory));
	}

	@Transactional
	public void deleteFaqCategory(Long faqCategoryId) {
		FaqCategory faqCategory = faqCategoryRepository.findByIdAndDeletedAtIsNull(faqCategoryId).orElseThrow(() ->
				new FaqException(FaqErrorCode.FAQ_CATEGORY_NOT_FOUND));

		if(faqRepository.existsAllByFaqCategoryId(faqCategoryId)){
			throw new FaqException(FaqErrorCode.FAQ_CATEGORY_IN_USE);
		}

		faqCategory.delete();
	}
}