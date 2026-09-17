package com.ubot.faq.service;

import com.ubot.faq.entity.FaqLog;
import com.ubot.faq.repository.FaqLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FaqLogService {
	private final FaqLogRepository faqLogRepository;

	public List<FaqLog> getFaqLogsByQuestionLogId(Long questionLogId) {
		return faqLogRepository.findByQuestionLogId(questionLogId);
	}

	public List<FaqLog> getFaqLogsByFaqId(Long faqId){
		return faqLogRepository.findByFaqId(faqId);
	}
}
