package com.ubot.store.dto;

/**
 * @param distanceKm 요청한 현재 위치에서 매장까지의 직선거리(km). 현재 위치를 전달하지 않으면 {@code null}입니다.
 */
public record StoreListResponseDto(
        long storeId,
        String storeName,
        String sido,
        String sigungu,
        String address,
        String phoneNumber,
        String businessHours,
        double latitude,
        double longitude,
        Double distanceKm
) {}
