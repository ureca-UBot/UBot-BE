package com.ubot.store.dto;

public record StoreListResponseDto(
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
