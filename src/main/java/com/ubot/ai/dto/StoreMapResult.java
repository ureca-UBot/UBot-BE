package com.ubot.ai.dto;

import com.ubot.store.dto.NearbyStoreResponseDto;
import java.util.List;

/**
 * 매장 조회 도구가 실제로 조회한 결과입니다. 채팅 화면이 지도에 그릴 수 있게 그대로 응답에 담습니다.
 * placeName이 null이면 사용자가 보낸 현재 위치를 기준으로 조회한 결과입니다.
 */
public record StoreMapResult(
        Location center,
        String placeName,
        double radiusKm,
        List<NearbyStoreResponseDto> stores) {

    public StoreMapResult {
        stores = stores == null ? List.of() : List.copyOf(stores);
    }
}
