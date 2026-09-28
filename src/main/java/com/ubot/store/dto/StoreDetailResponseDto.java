package com.ubot.store.dto;

import java.util.List;

/**
 * @param distanceKm 요청한 현재 위치에서 매장까지의 직선거리(km). 현재 위치를 전달하지 않으면 {@code null}입니다.
 */
public record StoreDetailResponseDto(
        long storeId,
        String storeName,
        String sido,
        String sigungu,
        String address,
        String phoneNumber,
        String businessHours,
        double latitude,
        double longitude,
        Double distanceKm,
        List<ServiceResponseDto> services
) {
    public record ServiceResponseDto(
            String code,
            String name
    ) {}
}
