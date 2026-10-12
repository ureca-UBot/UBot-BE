package com.ubot.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ubot.plan.entity.Plan;
import com.ubot.subscription.dto.response.SubscriptionResponseDto;
import com.ubot.subscription.entity.Subscription;

class SubscriptionResponseDtoTest {
	@ParameterizedTest
	@CsvSource({"1,*", "123,***", "1234,****", "12345,*2345"})
	@DisplayName("짧은 SIM 식별번호도 전체가 노출되지 않게 마스킹한다")
	void masksShortIdentifiers(String identifier, String expected) {
		var result = SubscriptionResponseDto.from(Subscription.builder()
				.plan(mock(Plan.class)).simIdentifier(identifier).build());
		assertThat(result.maskedSimIdentifier()).isEqualTo(expected);
		assertThat(result.maskedSimIdentifier()).isNotEqualTo(identifier);
	}
}
