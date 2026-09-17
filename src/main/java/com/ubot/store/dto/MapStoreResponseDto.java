package com.ubot.store.dto;

public record MapStoreResponseDto(
        long storeId,
        String storeName,
        String sido,
        String sigungu,
        String address,
        String phoneNumber,
        String businessHours,
        double latitude,
        double longitude
) {}
