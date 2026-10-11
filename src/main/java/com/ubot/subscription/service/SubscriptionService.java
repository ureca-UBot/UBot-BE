package com.ubot.subscription.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.plan.dto.response.PlanDetailResponseDto;
import com.ubot.subscription.dto.response.SubscriptionAddonResponseDto;
import com.ubot.subscription.dto.response.SubscriptionBundleResponseDto;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.dto.response.SubscriptionRoamingResponseDto;
import com.ubot.subscription.repository.SubscriptionAddonRepository;
import com.ubot.subscription.repository.SubscriptionBundleRepository;
import com.ubot.subscription.repository.SubscriptionRepository;
import com.ubot.subscription.repository.SubscriptionRoamingRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionService {
	private final SubscriptionAccessService subscriptionAccessService;
	private final SubscriptionRepository subscriptionRepository;
	private final SubscriptionBundleRepository subscriptionBundleRepository;
	private final SubscriptionAddonRepository subscriptionAddonRepository;
	private final SubscriptionRoamingRepository subscriptionRoamingRepository;

	public List<SubscriptionResponseDto> getMySubscriptionList(Long userId) {
		return subscriptionRepository.findAllByUserId(userId).stream()
				.map(SubscriptionResponseDto::from).toList();
	}

	public SubscriptionResponseDto getMySubscription(Long userId, Long subscriptionId) {
		return SubscriptionResponseDto.from(subscriptionAccessService.getOwnedSubscription(userId, subscriptionId));
	}

	public PlanDetailResponseDto getMyPlan(Long userId, Long subscriptionId) {
		// 판매 중지된 요금제도 기존 가입자는 조회할 수 있다.
		return PlanDetailResponseDto.from(subscriptionAccessService.getOwnedSubscription(userId, subscriptionId).getPlan());
	}

	public List<SubscriptionBundleResponseDto> getMyBundleList(Long userId, Long subscriptionId) {
		subscriptionAccessService.getOwnedSubscription(userId, subscriptionId);
		return subscriptionBundleRepository.findAllBySubscriptionIdAndUserId(subscriptionId, userId).stream()
				.map(SubscriptionBundleResponseDto::from).toList();
	}

	public List<SubscriptionAddonResponseDto> getMyAddonList(Long userId, Long subscriptionId) {
		subscriptionAccessService.getOwnedSubscription(userId, subscriptionId);
		return subscriptionAddonRepository.findAllBySubscriptionIdAndUserId(subscriptionId, userId).stream()
				.map(SubscriptionAddonResponseDto::from).toList();
	}

	public List<SubscriptionRoamingResponseDto> getMyRoamingList(Long userId, Long subscriptionId) {
		subscriptionAccessService.getOwnedSubscription(userId, subscriptionId);
		return subscriptionRoamingRepository.findAllBySubscriptionIdAndUserId(subscriptionId, userId).stream()
				.map(SubscriptionRoamingResponseDto::from).toList();
	}
}
