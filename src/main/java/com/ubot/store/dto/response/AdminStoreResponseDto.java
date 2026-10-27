package com.ubot.store.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.ubot.store.entity.ServiceType;
import com.ubot.store.entity.Store;

public record AdminStoreResponseDto(
        Long storeId,
        String storeName,
        String sido,
        String sigungu,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String phoneNumber,
        String businessHours,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ServiceResponse> services
) {

    public static AdminStoreResponseDto from(Store store) {
        List<ServiceResponse> services = store.getServiceTypes().stream()
                .sorted((left, right) -> left.getServiceCode().compareTo(right.getServiceCode()))
                .map(ServiceResponse::from)
                .toList();

        return new AdminStoreResponseDto(
                store.getStoreId(),
                store.getStoreName(),
                store.getSido(),
                store.getSigungu(),
                store.getAddress(),
                store.getLatitude(),
                store.getLongitude(),
                store.getPhoneNumber(),
                store.getBusinessHours(),
                store.isActive(),
                store.getCreatedAt(),
                store.getUpdatedAt(),
                services
        );
    }

    public record ServiceResponse(String code, String name) {

        private static ServiceResponse from(ServiceType serviceType) {
            return new ServiceResponse(serviceType.getServiceCode(), serviceType.getServiceName());
        }
    }
}
