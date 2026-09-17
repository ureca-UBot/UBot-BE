package com.ubot.store.dto;

public record NearbyStoreResponse(
        long storeId,
        String storeName,
        String sido,
        String sigungu,
        String address,
        String phoneNumber,
        String businessHours,
        double latitude,
        double longitude,
        double distanceKm
) {}
