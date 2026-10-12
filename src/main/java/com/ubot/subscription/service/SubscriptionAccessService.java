package com.ubot.subscription.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.subscription.entity.Subscription;
import com.ubot.subscription.exception.SubscriptionErrorCode;
import com.ubot.subscription.exception.SubscriptionException;
import com.ubot.subscription.repository.SubscriptionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionAccessService {
	private final SubscriptionRepository subscriptionRepository;

	// userId는 컨트롤러의 인증 정보 또는 도구의 서버 문맥에서 전달한다.
	// 타인 회선과 없는 회선은 동일하게 처리해 존재 여부를 노출하지 않는다.
	public Subscription getOwnedSubscription(Long userId, Long subscriptionId) {
		return subscriptionRepository.findBySubscriptionIdAndUserId(subscriptionId, userId)
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorCode.SUBSCRIPTION_NOT_FOUND));
	}
}
