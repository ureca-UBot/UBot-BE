package com.ubot.billing.service;

import java.time.YearMonth;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.common.GlobalException;
import com.ubot.common.PageResponseDto;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.billing.dto.response.BillingRecordResponseDto;
import com.ubot.billing.repository.BillingRecordRepository;
import com.ubot.subscription.service.SubscriptionAccessService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BillingService {
	private final SubscriptionAccessService subscriptionAccessService;
	private final BillingRecordRepository billingRecordRepository;

	public PageResponseDto<BillingRecordResponseDto> getMyBillingRecordList(
			Long userId, Long subscriptionId, YearMonth month, int page, int size) {
		subscriptionAccessService.getOwnedSubscription(userId, subscriptionId);
		if (page < 0 || size < 1 || size > 100
				|| month != null && (month.getYear() < 1 || month.getYear() > 9999)) {
			throw new GlobalException(CommonErrorCode.INVALID_PARAMETER);
		}
		PageRequest pageRequest = PageRequest.of(page, size,
				Sort.by(Sort.Direction.DESC, "billingMonth", "billingId"));
		var records = month == null
				? billingRecordRepository.findAllBySubscriptionIdAndUserId(subscriptionId, userId, pageRequest)
				: billingRecordRepository.findAllBySubscriptionIdAndUserIdAndBillingMonth(
						subscriptionId, userId, month.atDay(1), pageRequest);
		return PageResponseDto.from(records.map(BillingRecordResponseDto::from));
	}
}
