package com.ubot.chat.job.dto;

import com.ubot.ai.dto.Location;
import com.ubot.ranking.enums.RegionSido;

// 접수 때만 알 수 있어 Worker에 넘기는 요청 정보. 작업이 끝나면 지움
// 좌표는 내 위치 요청일 때만 있음. 지역은 접속 IP로 정하고 IP는 저장 안 함
public record ChatRequestContext(Double latitude, Double longitude, RegionSido region) {
	public static final ChatRequestContext EMPTY = new ChatRequestContext(null, null, null);

	public static ChatRequestContext of(Location location, RegionSido region) {
		return location == null
				? new ChatRequestContext(null, null, region)
				: new ChatRequestContext(location.latitude(), location.longitude(), region);
	}

	public Location location() {
		return latitude == null ? null : new Location(latitude, longitude);
	}
}
