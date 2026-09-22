package com.ubot.faq.service;


import com.pgvector.PGvector;
import com.ubot.common.ErrorCode;
import com.ubot.common.PageResponseDto;
import com.ubot.common.exception.FaqException;
import com.ubot.common.exception.UserException;
import com.ubot.embedding.service.EmbeddingService;
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

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class FaqService {

	private final FaqRepository faqRepository;
	private final FaqCategoryRepository faqCategoryRepository;
	private final OldFaqRepository oldFaqRepository;
	private final UserRepository userRepository;
	private final EmbeddingService embeddingService;


//	Todo: 중복되는 FAQ가 존재하는지를 확인하는 내용이 필요해보이는데, 어떻게 할지는 미정
	@Transactional
	public FaqResponseDto createFaq(FaqCreateRequestDto requestDto, Long adminId){

		User admin = userRepository.findById(adminId).orElseThrow(() -> new UserException(ErrorCode.USER_NOT_FOUND));
		FaqCategory category = faqCategoryRepository.findByIdAndDeletedAtIsNull(requestDto.categoryId()).orElseThrow(() -> new FaqException(ErrorCode.FAQ_CATEGORY_NOT_FOUND));

		PGvector vector = embeddingService.embedText(requestDto.question());

		Faq faq = Faq.builder()
				.admin(admin)
				.faqCategory(category)
				.question(requestDto.question())
				.answer(requestDto.answer())
				.createdAt(LocalDateTime.now())
				.updatedAt(LocalDateTime.now())
				.vector(vector)
				.build();

		return FaqResponseDto.from(faqRepository.save(faq));
	}

	public FaqResponseDto getActiveFaq(Long faqId){
		return FaqResponseDto.from(faqRepository.findActiveById(faqId).orElseThrow(() -> new FaqException(ErrorCode.FAQ_NOT_FOUND)));
	}

	public PageResponseDto<FaqResponseDto> getActiveFaqList(int page, int size){
		Page<FaqResponseDto> faqPage = faqRepository.
				findAllActives(PageRequest.of(page, size, Sort.by(
						Sort.Order.desc("createdAt"),
						Sort.Order.desc("id")
				)))
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

	public PageResponseDto<FaqResponseDto> searchActiveFaq(int page, int size, String keyword){
		Page<FaqResponseDto> faqPage = faqRepository.
				findActivesByKeyword(keyword, PageRequest.of(page, size, Sort.by(
						Sort.Order.desc("createdAt"),
						Sort.Order.desc("id")
				)))
				.map(FaqResponseDto::from);

		return PageResponseDto.from(faqPage);
	}

	public PageResponseDto<FaqResponseDto> getFaqListByFaqCategoryId(int page, int size, Long faqCategoryId){
		Page<FaqResponseDto> faqPage = faqRepository.
				findAllByFaqCategoryId(faqCategoryId, PageRequest.of(page, size, Sort.by(
						Sort.Order.desc("createdAt"),
						Sort.Order.desc("id")
				)))
				.map(FaqResponseDto::from);

		return PageResponseDto.from(faqPage);
	}

	@Transactional
	public FaqResponseDto updateActiveFaq(FaqUpdateRequestDto requestDto, Long adminId){

		Faq faq = faqRepository.findActiveById(requestDto.id()).orElseThrow(() -> new FaqException(ErrorCode.FAQ_NOT_FOUND));
		User updatedBy = userRepository.findById(adminId).orElseThrow(() -> new UserException(ErrorCode.USER_NOT_FOUND));

		OldFaq oldFaq = OldFaq.from(faq, updatedBy);
		oldFaqRepository.save(oldFaq);

		FaqCategory category = faqCategoryRepository.findByNameAndDeletedAtIsNull(requestDto.category()).orElseThrow(() -> new FaqException(ErrorCode.FAQ_CATEGORY_NOT_FOUND));

		if(!faq.getQuestion().equals(requestDto.question())) {
			PGvector vector = embeddingService.embedText(requestDto.question());

			faq.update(
					category,
					requestDto.question(),
					requestDto.answer(),
					vector
			);
		}
		else {
			faq.update(
					category,
					requestDto.question(),
					requestDto.answer(),
					faq.getVector()
			);
		}

		return FaqResponseDto.from(faqRepository.save(faq));
	}

	@Transactional
	public void deleteFaq(Long faqId){
		Faq faq = faqRepository.findActiveById(faqId).orElseThrow(() -> new FaqException(ErrorCode.FAQ_NOT_FOUND));

		faq.delete();
	}
}