package com.ubot.direction.service;

import org.springframework.stereotype.Service;

import com.ubot.direction.client.KakaoDirectionsClient;
import com.ubot.direction.dto.DirectionsMode;
import com.ubot.direction.dto.DirectionsResponseDto;
import com.ubot.direction.dto.DirectionsResponseDto.PointDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.service.StoreService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DirectionsService {

    private final StoreService storeService;
    private final KakaoDirectionsClient kakaoDirectionsClient;

    /** 현재 위치에서 매장까지의 경로를 조회합니다. 카카오 정책상 결과를 저장하지 않고 매번 실시간으로 조회합니다. */
    public DirectionsResponseDto getStoreDirections(
            long storeId,
            DirectionsMode mode,
            double latitude,
            double longitude
    ) {
        StoreDetailResponseDto store = storeService.getStore(storeId);

        return kakaoDirectionsClient.getDirections(
                mode,
                new PointDto(latitude, longitude),
                new PointDto(store.latitude(), store.longitude())
        );
    }
}
