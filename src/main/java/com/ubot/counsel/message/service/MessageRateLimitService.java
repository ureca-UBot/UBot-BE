package com.ubot.counsel.message.service;

import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.common.exception.CounselErrorCode;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.counsel.message.repository.MessageRepository;
import org.springframework.stereotype.Service;

@Service
public class MessageRateLimitService {
	private final MessageRepository repository;
	private final CounselProperties config;
	public MessageRateLimitService(MessageRepository repository, CounselProperties config) {
		this.repository = repository;
		this.config = config;
	}
	public void validateRequestRate(long userId) {
		if (repository.incrementRequestCount(userId, config.rateWindowSeconds()) > config.rateMaxRequests()) {
			throw new CounselException(CounselErrorCode.RATE_LIMITED);
		}
	}
}
