package com.ubot.faq.service;

import com.ubot.common.ErrorCode;
import com.ubot.common.exception.FaqException;
import com.ubot.faq.dto.reqeust.FaqCategoryCreateRequestDto;
import com.ubot.faq.dto.reqeust.FaqCategoryUpdateRequestDto;
import com.ubot.faq.dto.reqeust.FaqCreateRequestDto;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.repository.FaqCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FaqCategoryService {
	private final FaqCategoryRepository faqCategoryRepository;

	@Transactional
	public FaqCategory createFaqCategory(FaqCategoryCreateRequestDto requestDto) {
		String categoryName = requestDto.name();
		if(faqCategoryRepository.findByName(categoryName).isPresent()) {
			throw new FaqException(ErrorCode.FAQ_CATEGORY_EXIST, categoryName + "는 이미 존재하는 카테고리명입니다.");
		}

		FaqCategory faqCategory = FaqCategory.builder()
				.name(categoryName)
				.createdAt(LocalDateTime.now())
				.build();

		return faqCategoryRepository.save(faqCategory);
	}

	public List<FaqCategory> getFaqCategories() {
		return faqCategoryRepository.findAll();
	}

	public List<FaqCategory> searchFaqCategories(String keyword){
		return faqCategoryRepository.findByKeyword(keyword);
	}

	@Transactional
	public FaqCategory updateFaqCategory(FaqCategoryUpdateRequestDto requestDto) {
		FaqCategory faqCategory = faqCategoryRepository.findByName(requestDto.beforeName()).orElseThrow(() -> new FaqException(ErrorCode.FAQ_CATEGORY_NOT_FOUND));

		faqCategory.update(requestDto.afterName());

		return faqCategoryRepository.save(faqCategory);
	}

	@Transactional
	public void deleteFaqCategory(Long faqCategoryId) {
		faqCategoryRepository.deleteById(faqCategoryId);
	}
}
