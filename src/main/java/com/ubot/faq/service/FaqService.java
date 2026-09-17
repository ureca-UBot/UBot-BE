package com.ubot.faq.service;


import com.ubot.common.ErrorCode;
import com.ubot.common.exception.FaqException;
import com.ubot.common.exception.UserException;
import com.ubot.faq.dto.reqeust.FaqCreateRequestDto;
import com.ubot.faq.dto.reqeust.FaqUpdateRequestDto;
import com.ubot.faq.entity.Faq;
import com.ubot.faq.entity.FaqCategory;
import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.repository.FaqCategoryRepository;
import com.ubot.faq.repository.OldFaqRepository;
import com.ubot.faq.repository.FaqRepository;
import com.ubot.user.entity.User;
import com.ubot.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FaqService {

	private final FaqRepository faqRepository;
	private final FaqCategoryRepository faqCategoryRepository;
	private final OldFaqRepository oldFaqRepository;
	private final UserRepository userRepository;
//	private final EmbeddingService embeddingService;


//	Todo: 중복되는 FAQ가 존재하는지를 확인하는 내용이 필요해보이는데, 어떻게 할지는 미정
	@Transactional
	public Faq createFaq(FaqCreateRequestDto requestDto, Long adminId){

		User admin = userRepository.findById(adminId).orElseThrow(() -> new UserException(ErrorCode.USER_NOT_FOUND));
		FaqCategory category = faqCategoryRepository.findByName(requestDto.category()).orElseThrow(() -> new FaqException(ErrorCode.FAQ_CATEGORY_NOT_FOUND));

//		Todo: 해당 FAQ의 question을 Embedding화하여 저장
//		PGVector vector = embeddingService.embedText(faq.getQuestion());
//		if(vector == null)
//			throw new FaqException(ErrorCode.FAQ_VECTOR_CREATE_FAILURE);


		Faq faq = Faq.builder()
				.admin(admin)
				.faqCategory(category)
				.question(requestDto.question())
				.answer(requestDto.answer())
				.createdAt(LocalDateTime.now())
				.updatedAt(LocalDateTime.now())
//				.vector(vector)
				.build();

		return faqRepository.save(faq);
	}

	public Faq getActiveFaq(Long faqId){
		return faqRepository.findActiveById(faqId).orElseThrow(() -> new FaqException(ErrorCode.FAQ_NOT_FOUND));
	}

	public List<Faq> getActiveFaqList(){
		return faqRepository.findAllActives();
	}

	public List<Faq> getDeletedFaqList(){
		return faqRepository.findAllDeletedFaq();
	}

	public List<Faq> searchActiveFaq(String keyword){
		return faqRepository.findActivesByKeyword(keyword);
	}

	@Transactional
	public Faq updateActiveFaq(FaqUpdateRequestDto requestDto, Long adminId){

		Faq faq = faqRepository.findActiveById(requestDto.id()).orElseThrow(() -> new FaqException(ErrorCode.FAQ_NOT_FOUND));
		User updatedBy = userRepository.findById(adminId).orElseThrow(() -> new UserException(ErrorCode.USER_NOT_FOUND));

		OldFaq oldFaq = OldFaq.from(faq, updatedBy);
		oldFaqRepository.save(oldFaq);

		FaqCategory category = faqCategoryRepository.findByName(requestDto.category()).orElseThrow(() -> new FaqException(ErrorCode.FAQ_CATEGORY_NOT_FOUND));

//		Todo: vector 로직 들어오면 이거 추가
//		PGvector vector = embeddingService.embedText(requestDto.question());
//
//		if (vector == null) {
//			throw new FaqException(
//					ErrorCode.FAQ_VECTOR_CREATE_FAILURE
//			);
//		}

		faq.update(
				category,
				requestDto.question(),
				requestDto.answer(),
				null //Todo: vector가 들어가야 함
		);

		return faqRepository.save(faq);
	}

	@Transactional
	public void deleteFaq(Long faqId){
		Faq faq = faqRepository.findActiveById(faqId).orElseThrow(() -> new FaqException(ErrorCode.FAQ_NOT_FOUND));

		faq.delete();
	}
}
