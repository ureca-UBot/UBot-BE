package com.ubot.faq.service;

import com.ubot.common.PageResponseDto;
import com.ubot.faq.dto.response.OldFaqResponseDto;
import com.ubot.faq.repository.OldFaqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OldFaqService {
	private final OldFaqRepository oldFaqRepository;

	public PageResponseDto<OldFaqResponseDto> getOldFaqByFaqId(int page, int size, Long faqId){
		Page<OldFaqResponseDto> oldFaqPage = oldFaqRepository.
				findByFaqId(faqId, PageRequest.of(page, size, Sort.by(
						Sort.Order.desc("version")
				)))
				.map(OldFaqResponseDto::from);

		return PageResponseDto.from(oldFaqPage);
	}
}