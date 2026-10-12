package com.ubot.contract.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ubot.contract.dto.response.SubscriptionContractResponseDto;
import com.ubot.contract.enums.ContractStatus;
import com.ubot.contract.repository.SubscriptionContractRepository;
import com.ubot.subscription.service.SubscriptionAccessService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContractService {
	private final SubscriptionAccessService subscriptionAccessService;
	private final SubscriptionContractRepository subscriptionContractRepository;

	public SubscriptionContractResponseDto getMyContract(Long userId, Long subscriptionId) {
		subscriptionAccessService.getOwnedSubscription(userId, subscriptionId);
		return subscriptionContractRepository.findBySubscriptionIdAndUserIdAndStatus(
				subscriptionId, userId, ContractStatus.ACTIVE)
				.map(SubscriptionContractResponseDto::from).orElse(null);
	}

	public List<SubscriptionContractResponseDto> getMyContractList(Long userId, Long subscriptionId) {
		subscriptionAccessService.getOwnedSubscription(userId, subscriptionId);
		return subscriptionContractRepository.findAllBySubscriptionIdAndUserId(subscriptionId, userId).stream()
				.map(SubscriptionContractResponseDto::from).toList();
	}
}
