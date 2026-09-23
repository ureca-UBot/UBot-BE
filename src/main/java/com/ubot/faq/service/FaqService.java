package com.ubot.faq.service;

import com.ubot.common.PageResponseDto;
import com.ubot.faq.dto.request.FaqRestoreRequestDto;
import com.ubot.faq.exception.FaqErrorCode;
import com.ubot.faq.exception.FaqException;
import com.ubot.user.exception.UserErrorCode;
import com.ubot.user.exception.UserException;
import com.ubot.faq.dto.request.FaqCreateRequestDto;
import com.ubot.faq.dto.request.FaqUpdateRequestDto;
import com.ubot.faq.dto.response.FaqResponseDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.repository.FaqCategoryRepository;
import com.ubot.faq.repository.OldFaqRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.user.entity.User;
import com.ubot.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FaqService {

	private final FaqRepository faqRepository;
	private final FaqVectorService faqVectorService;
	private final FaqCategoryRepository faqCategoryRepository;
	private final OldFaqRepository oldFaqRepository;
	private final UserRepository userRepository;


	//	Todo: 중복되는 FAQ가 존재하는지를 확인하는 내용이 필요해보이는데, 어떻게 할지는 미정
	@Transactional
	public FaqResponseDto createFaq(FaqCreateRequestDto requestDto, Long adminId){

		User admin = userRepository.findById(adminId).orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));
		FaqCategory category = faqCategoryRepository.findByIdAndDeletedAtIsNull(requestDto.categoryId()).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_CATEGORY_NOT_FOUND));

		Faq faq = Faq.builder()
				.admin(admin)
				.faqCategory(category)
				.question(requestDto.question())
				.answer(requestDto.answer())
				.createdAt(LocalDateTime.now())
				.updatedAt(LocalDateTime.now())
				.build();

		Faq savedFaq = faqRepository.save(faq);
		faqVectorService.saveVectorForFaq(savedFaq.getId(), requestDto.question());

		return FaqResponseDto.from(savedFaq);
	}

	public FaqResponseDto getActiveFaq(Long faqId){
		return FaqResponseDto.from(faqRepository.findActiveById(faqId).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND)));
	}

	public PageResponseDto<FaqResponseDto> getActiveFaqList(int page, int size, String keyword, Long categoryId){
		String normalizedKeyword = StringUtils.hasText(keyword) ? keyword : "";

		Page<FaqResponseDto> faqPage = faqRepository.
				findAllActives(PageRequest.of(page, size, Sort.by(
						Sort.Order.desc("createdAt"),
						Sort.Order.desc("id")
				)), normalizedKeyword, categoryId)
				.map(FaqResponseDto::from);


		return PageResponseDto.from(faqPage);
	}

	public PageResponseDto<FaqResponseDto> getDeletedFaqList(int page, int size){
		Page<FaqResponseDto> faqPage = faqRepository.
				findAllDeletedFaq(PageRequest.of(page, size, Sort.by(
						Sort.Order.desc("deletedAt"),
						Sort.Order.desc("id")
				)))
				.map(FaqResponseDto::from);

		return PageResponseDto.from(faqPage);
	}

	public PageResponseDto<FaqResponseDto> getActiveFaqListByFaqCategoryId(int page, int size, Long faqCategoryId){
		Page<FaqResponseDto> faqPage = faqRepository.
				findAllByFaqCategoryIdAndDeletedAtIsNull(faqCategoryId, PageRequest.of(page, size, Sort.by(
						Sort.Order.desc("createdAt"),
						Sort.Order.desc("id")
				)))
				.map(FaqResponseDto::from);

		return PageResponseDto.from(faqPage);
	}

	@Transactional
	public FaqResponseDto updateActiveFaq(FaqUpdateRequestDto requestDto, Long adminId){

		Faq faq = faqRepository.findActiveById(requestDto.id()).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));
		User updatedBy = userRepository.findById(adminId).orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));

		OldFaq oldFaq = OldFaq.from(faq, updatedBy);
		oldFaqRepository.saveAndFlush(oldFaq);
		faqVectorService.saveVectorForOldFaq(faq.getId(), faq.getVersion());

		FaqCategory category = faqCategoryRepository.findByIdAndDeletedAtIsNull(requestDto.categoryId()).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_CATEGORY_NOT_FOUND));

		if(!faq.getQuestion().equals(requestDto.question())) {
			faqVectorService.saveVectorForFaq(faq.getId(), requestDto.question());
		}

		faq.update(
				category,
				requestDto.question(),
				requestDto.answer()
		);
		return FaqResponseDto.from(faqRepository.save(faq));
	}

	@Transactional
	public void deleteFaq(Long faqId){
		Faq faq = faqRepository.findActiveById(faqId).orElseThrow(() -> new FaqException(FaqErrorCode.FAQ_NOT_FOUND));

		faq.delete();
	}

	@Transactional
	public void restoreFaqs(FaqRestoreRequestDto requestDto){
		List<Faq> faqs = faqRepository.findDeletedFaqByFaqIds(requestDto.faqIds());
		for(Faq faq : faqs){
			faq.restore();
		}
	}
}