package com.ubot.usage.service;

import java.time.YearMonth;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.GlobalException;
import com.ubot.common.PageResponseDto;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.usage.dto.response.UsageRecordResponseDto;
import com.ubot.usage.repository.UsageRecordRepository;
import com.ubot.subscription.service.SubscriptionAccessService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UsageService {
	private final SubscriptionAccessService subscriptionAccessService;
	private final UsageRecordRepository usageRecordRepository;

	public PageResponseDto<UsageRecordResponseDto> getMyUsageRecordList(
			Long userId, Long subscriptionId, YearMonth month, int page, int size) {
		subscriptionAccessService.getOwnedSubscription(userId, subscriptionId);
		if (page < 0 || size < 1 || size > 100
				|| month != null && (month.getYear() < 1 || month.getYear() > 9999)) {
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
		PageRequest pageRequest = PageRequest.of(page, size,
				Sort.by(Sort.Direction.DESC, "billingMonth", "usageId"));
		var records = month == null
				? usageRecordRepository.findAllBySubscriptionIdAndUserId(subscriptionId, userId, pageRequest)
				: usageRecordRepository.findAllBySubscriptionIdAndUserIdAndBillingMonth(
						subscriptionId, userId, month.atDay(1), pageRequest);
		return PageResponseDto.from(records.map(UsageRecordResponseDto::from));
	}
}
