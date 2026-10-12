package com.ubot.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ubot.usage.dto.response.UsageRecordResponseDto;
import com.ubot.usage.entity.UsageRecord;

@DisplayName("사용량 스냅샷과 잔여량 계산 테스트")
class UsageRecordResponseDtoTest {
	@Test
	@DisplayName("제공량 또는 무제한 여부가 없으면 잔여량을 추측하지 않는다")
	void leavesIncompleteAllowanceUnknown() {
		var result = from(Map.of("dataAmountMb", 5000L, "voiceUnlimited", false));
		assertThat(result.remainingDataMb()).isNull();
		assertThat(result.remainingVoiceMinutes()).isNull();
		assertThat(result.remainingSmsCount()).isNull();
	}

	@Test
	@DisplayName("무제한이면 사용량과 제공량 숫자가 있어도 잔여량을 반환하지 않는다")
	void doesNotCalculateUnlimitedRemaining() {
		var result = from(Map.of("dataAmountMb", 5000, "dataUnlimited", true,
				"voiceMinutes", 100, "voiceUnlimited", true, "smsCount", 50, "smsUnlimited", true));
		assertThat(result.remainingDataMb()).isNull();
		assertThat(result.remainingVoiceMinutes()).isNull();
		assertThat(result.remainingSmsCount()).isNull();
	}

	@Test
	@DisplayName("범위를 벗어난 숫자, 음수, 문자열, 소수 제공량은 계산에 사용하지 않는다")
	void rejectsInvalidSnapshotAmounts() {
		var result = from(Map.of("dataAmountMb", new BigDecimal("9223372036854775808"), "dataUnlimited", false,
				"voiceMinutes", 2147483648L, "voiceUnlimited", false, "smsCount", -1, "smsUnlimited", false));
		assertThat(result.allowance().dataAmountMb()).isNull();
		assertThat(result.allowance().voiceMinutes()).isNull();
		assertThat(result.allowance().smsCount()).isNull();
		var fractional = from(Map.of("dataAmountMb", new BigDecimal("123.5"), "voiceMinutes", "100"));
		assertThat(fractional.allowance().dataAmountMb()).isNull();
		assertThat(fractional.allowance().voiceMinutes()).isNull();
	}

	@Test
	@DisplayName("스냅샷이 없거나 비어 있으면 제공량과 잔여량은 정보 없음으로 반환한다")
	void handlesMissingSnapshot() {
		assertThat(from(null).allowance()).isNull();
		assertThat(from(Map.of()).allowance()).isNull();
		assertThat(from(null).remainingDataMb()).isNull();
	}

	@Test
	@DisplayName("유한 제공량을 소진하면 음수 대신 잔여량 0을 반환한다")
	void clampsExhaustedAllowance() {
		var result = from(Map.of("dataAmountMb", 100, "dataUnlimited", false,
				"voiceMinutes", 5, "voiceUnlimited", false, "smsCount", 1, "smsUnlimited", false));
		assertThat(result.remainingDataMb()).isZero();
		assertThat(result.remainingVoiceMinutes()).isZero();
		assertThat(result.remainingSmsCount()).isZero();
	}

	private UsageRecordResponseDto from(Map<String, Object> snapshot) {
		return UsageRecordResponseDto.from(UsageRecord.builder().billingMonth(LocalDate.of(2026, 10, 1))
				.dataUsedMb(1000L).voiceUsedMinutes(80).smsUsedCount(10).allowanceSnapshot(snapshot).build());
	}
}
