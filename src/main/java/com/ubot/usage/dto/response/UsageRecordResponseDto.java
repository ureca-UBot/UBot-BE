package com.ubot.usage.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import com.ubot.usage.entity.UsageRecord;

public record UsageRecordResponseDto(
		Long usageId,
		YearMonth billingMonth,
		Long dataUsedMb,
		Integer voiceUsedMinutes,
		Integer smsUsedCount,
		UsageAllowanceResponseDto allowance,
		Long remainingDataMb,
		Integer remainingVoiceMinutes,
		Integer remainingSmsCount,
		LocalDate aggregatedThroughDate,
		boolean finalized,
		LocalDateTime finalizedAt,
		Boolean fullMonthActive
) {
	public static UsageRecordResponseDto from(UsageRecord record) {
		UsageAllowanceResponseDto allowance = UsageAllowanceResponseDto.from(record.getAllowanceSnapshot());
		return new UsageRecordResponseDto(
				record.getUsageId(), YearMonth.from(record.getBillingMonth()),
				record.getDataUsedMb(), record.getVoiceUsedMinutes(), record.getSmsUsedCount(), allowance,
				allowance == null ? null : remaining(allowance.dataAmountMb(), allowance.dataUnlimited(), record.getDataUsedMb()),
				allowance == null ? null : remaining(allowance.voiceMinutes(), allowance.voiceUnlimited(), record.getVoiceUsedMinutes()),
				allowance == null ? null : remaining(allowance.smsCount(), allowance.smsUnlimited(), record.getSmsUsedCount()),
				record.getAggregatedThroughDate(), record.getFinalizedAt() != null,
				record.getFinalizedAt(), record.getFullMonthActive());
	}

	private static Long remaining(Long provided, Boolean unlimited, Long used) {
		return Boolean.FALSE.equals(unlimited) && provided != null && used != null
				? Math.max(0L, provided - used) : null;
	}

	private static Integer remaining(Integer provided, Boolean unlimited, Integer used) {
		return Boolean.FALSE.equals(unlimited) && provided != null && used != null
				? Math.max(0, provided - used) : null;
	}
}
