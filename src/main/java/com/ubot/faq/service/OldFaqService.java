package com.ubot.faq.service;

import com.ubot.faq.entity.OldFaq;
import com.ubot.faq.repository.OldFaqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OldFaqService {
	private final OldFaqRepository oldFaqRepository;

	public List<OldFaq> getOldFaqByFaqId(Long faqId){
		return oldFaqRepository.findByFaqId(faqId);
	}

	public List<OldFaq> getOldFaqByFaqCategoryId(Long faqCategoryId){
		return oldFaqRepository.findByFaqCategoryId(faqCategoryId);
	}

	@Transactional
	public void deleteByFaqId(Long faqId){
		oldFaqRepository.deleteByFaqId(faqId);
	}
}
