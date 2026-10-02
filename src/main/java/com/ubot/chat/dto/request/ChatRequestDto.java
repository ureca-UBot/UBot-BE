package com.ubot.chat.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 질문자 ID는 요청 본문 대신 로그인 인증 정보에서 확인합니다.
public record ChatRequestDto(
		@NotBlank
		@Size(max = 4000)
		String question,
		@DecimalMin("-90.0")
		@DecimalMax("90.0")
		Double latitude,
		@DecimalMin("-180.0")
		@DecimalMax("180.0")
		Double longitude
) {
	// 위도·경도는 함께 보내거나 함께 생략합니다. (StoreService의 좌표 쌍 규칙과 동일)
	@AssertTrue
	public boolean isLocationPairValid() {
		return (latitude == null) == (longitude == null);
	}
}
