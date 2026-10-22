package com.ubot.counsel.message.service;

import com.ubot.counsel.common.config.CounselProperties;
import com.ubot.counsel.common.exception.CounselErrorCode;
import com.ubot.counsel.common.exception.CounselException;
import com.ubot.counsel.message.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MessageRateLimitService {
	private final MessageRepository repository;
	private final CounselProperties config;
	public void validateRequestRate(long userId) {
		if (repository.incrementRequestCount(userId, config.rateWindowSeconds()) > config.rateMaxRequests()) {
			throw new CounselException(CounselErrorCode.RATE_LIMITED);
		}
	}
}
