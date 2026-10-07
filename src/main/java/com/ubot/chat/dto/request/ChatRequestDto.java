package com.ubot.chat.dto.request;

import com.ubot.ai.dto.Location;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 질문자 ID는 요청 본문 대신 로그인 인증 정보에서 확인합니다.
// 좌표는 위치 필요 응답(locationRequired)을 받은 화면이 useMyLocation과 함께 다시 보낼 때만 사용합니다.
public record ChatRequestDto(
		@NotBlank
		@Size(max = 4000)
		String question,
		@DecimalMin("-90.0")
		@DecimalMax("90.0")
		Double latitude,
		@DecimalMin("-180.0")
		@DecimalMax("180.0")
		Double longitude,
		Boolean useMyLocation
) {
	public ChatRequestDto {
		// Jackson 3은 boolean 필드가 요청에 없으면 오류를 내므로 Boolean으로 받고, 없으면 false로 둡니다.
		useMyLocation = Boolean.TRUE.equals(useMyLocation);
	}

	// 위도·경도는 함께 보내거나 함께 생략합니다. (StoreService의 좌표 쌍 규칙과 동일)
	@AssertTrue(message = "위도와 경도는 함께 보내야 합니다.")
	public boolean isLocationPairValid() {
		return (latitude == null) == (longitude == null);
	}

	// 현재 위치로 찾기 요청에는 좌표가 있어야 합니다. 경도는 isLocationPairValid가 함께 보장합니다.
	@AssertTrue(message = "현재 위치로 찾기 요청에는 좌표가 있어야 합니다.")
	public boolean isMyLocationValid() {
		// 내 위치 기준 요청인데 좌표가 없으면 무효
		if (useMyLocation && latitude == null) {
			return false;
		}
		return true;
	}

	/**
	 * 내 위치 기준 재요청일 때만 좌표를 Location으로 돌려줍니다. 그 외에는 좌표가 와도 null입니다.
	 * 좌표 쌍과 범위는 위 검증을 통과한 뒤에 호출되므로 항상 올바른 Location이 만들어집니다.
	 */
	public Location myLocation() {
		return useMyLocation ? new Location(latitude, longitude) : null;
	}
}
