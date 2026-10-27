package com.ubot.chat.dto.request;

import com.ubot.ai.dto.Location;
import com.ubot.faq.enums.Intent;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

// 재검색할 원본 답변은 본문 대신 Idempotency-Key 헤더로, 질문자는 로그인 인증 정보로 확인합니다.
// 좌표는 위치가 필요한 의도(매장)로 재검색할 때만 보내며, 보내지 않으면 위치 없이 검색합니다.
public record ChatResearchRequestDto(
        @NotNull Intent intent,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude) {
    // 위도·경도는 함께 보내거나 함께 생략합니다. (ChatRequestDto, StoreService의 좌표 쌍 규칙과 동일)
    @AssertTrue(message = "위도와 경도는 함께 보내야 합니다.")
    public boolean isLocationPairValid() {
        return (latitude == null) == (longitude == null);
    }

    /** 좌표를 보냈을 때만 Location을 돌려줍니다. 쌍과 범위는 위 검증을 통과한 뒤에 호출되므로 항상 올바른 값입니다. */
    public Location myLocation() {
        return latitude == null ? null : new Location(latitude, longitude);
    }
}