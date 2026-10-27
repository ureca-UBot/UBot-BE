package com.ubot.contract.dto.response;

import java.time.LocalDate;

import com.ubot.contract.entity.SubscriptionContract;
import com.ubot.contract.enums.InstallmentStatus;

public record InstallmentResponseDto(
		String deviceName,
		Integer deviceTotalAmount,
		Integer installmentMonths,
		Integer paidInstallmentMonths,
		Integer monthlyInstallmentAmount,
		Integer remainingInstallmentAmount,
		LocalDate installmentStartedAt,
		LocalDate installmentExpiresAt,
		InstallmentStatus installmentStatus
) {
	public static InstallmentResponseDto from(SubscriptionContract contract) {
		if (contract.getDeviceName() == null) {
			return null;
		}
		return new InstallmentResponseDto(
				contract.getDeviceName(), contract.getDeviceTotalAmount(), contract.getInstallmentMonths(),
				contract.getPaidInstallmentMonths(), contract.getMonthlyInstallmentAmount(),
				contract.getRemainingInstallmentAmount(), contract.getInstallmentStartedAt(),
				contract.getInstallmentExpiresAt(), contract.getInstallmentStatus());
	}
}
