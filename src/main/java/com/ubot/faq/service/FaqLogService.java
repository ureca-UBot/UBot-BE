package com.ubot.faq.service;

import com.ubot.common.PageResponseDto;
import com.ubot.faq.dto.response.FaqLogResponseDto;
import com.ubot.faq.entity.FaqLog;
import com.ubot.faq.repository.FaqLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FaqLogService {
	private final FaqLogRepository faqLogRepository;

	public PageResponseDto<FaqLogResponseDto> getFaqLogsByQuestionLogId(int page, int size, Long questionLogId) {
		Page<FaqLogResponseDto> faqLogPage = faqLogRepository
				.findByQuestionLogId(questionLogId, PageRequest.of(page, size, Sort.by(
						Sort.Order.desc("createdAt"),
						Sort.Order.desc("id")
				)))
				.map(FaqLogResponseDto::from);

		return PageResponseDto.from(faqLogPage);
	}

	public PageResponseDto<FaqLogResponseDto> getFaqLogsByFaqId(int page, int size, Long faqId){
		Page<FaqLogResponseDto> faqLogPage = faqLogRepository
				.findByFaqId(faqId, PageRequest.of(page, size, Sort.by(
						Sort.Order.desc("createdAt"),
						Sort.Order.desc("id")
				)))
				.map(FaqLogResponseDto::from);

		return PageResponseDto.from(faqLogPage);
	}
}