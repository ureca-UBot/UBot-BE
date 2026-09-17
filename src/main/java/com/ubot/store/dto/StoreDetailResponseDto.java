package com.ubot.store.dto;

import java.util.List;

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
        List<ServiceResponseDto> services
) {
    public record ServiceResponseDto(
            String code,
            String name
    ) {}
}
