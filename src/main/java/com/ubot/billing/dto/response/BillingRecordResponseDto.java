package com.ubot.billing.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import com.ubot.billing.entity.BillingRecord;
import com.ubot.billing.enums.PaymentMethod;
import com.ubot.billing.enums.PaymentStatus;

public record BillingRecordResponseDto(
		Long billingId,
		YearMonth billingMonth,
		Integer baseFee,
		Integer addonFee,
		Integer roamingFee,
		Integer extraFee,
		Integer discountAmount,
		Integer totalAmount,
		LocalDate dueDate,
		PaymentStatus paymentStatus,
		Integer paidAmount,
		PaymentMethod paymentMethod,
		LocalDateTime paidAt
) {
	public static BillingRecordResponseDto from(BillingRecord record) {
		return new BillingRecordResponseDto(
				record.getBillingId(), YearMonth.from(record.getBillingMonth()), record.getBaseFee(),
				record.getAddonFee(), record.getRoamingFee(), record.getExtraFee(), record.getDiscountAmount(),
				record.getTotalAmount(), record.getDueDate(), record.getPaymentStatus(),
				record.getPaidAmount(), record.getPaymentMethod(), record.getPaidAt());
	}
}
