package com.ubot.contract.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.ubot.contract.entity.SubscriptionContract;
import com.ubot.contract.enums.ContractStatus;
import com.ubot.contract.enums.ContractType;

public record SubscriptionContractResponseDto(
		Long contractId,
		ContractType contractType,
		LocalDate startedAt,
		LocalDate expiresAt,
		Integer discountAmount,
		ContractStatus status,
		LocalDateTime canceledAt,
		InstallmentResponseDto installment
) {
	public static SubscriptionContractResponseDto from(SubscriptionContract contract) {
		return new SubscriptionContractResponseDto(
				contract.getContractId(), contract.getContractType(), contract.getStartedAt(),
				contract.getExpiresAt(), contract.getDiscountAmount(), contract.getStatus(),
				contract.getCanceledAt(), InstallmentResponseDto.from(contract));
	}
}
