package com.ubot.store.dto;

import java.util.List;

public record StoreDetailResponse(
        long storeId,
        String storeName,
        String sido,
        String sigungu,
        String address,
        String phoneNumber,
        String businessHours,
        double latitude,
        double longitude,
        List<ServiceResponse> services
) {
    public record ServiceResponse(
            String code,
            String name
    ) {}
}
