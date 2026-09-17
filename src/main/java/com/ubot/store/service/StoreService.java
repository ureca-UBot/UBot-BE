package com.ubot.store.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.ubot.common.ErrorCode;
import com.ubot.common.exception.GlobalException;
import com.ubot.store.dto.MapStoreResponse;
import com.ubot.store.dto.NearbyStoreResponse;
import com.ubot.store.dto.StoreDetailResponse;
import com.ubot.store.repository.StoreRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;

    public StoreDetailResponse findById(long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new GlobalException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "매장을 찾을 수 없습니다."
                ));
    }

    public List<NearbyStoreResponse> findNearby(
            double latitude,
            double longitude,
            double radiusKm,
            String type,
            int limit
    ) {
        return storeRepository.findNearby(
                latitude,
                longitude,
                radiusKm * 1_000,
                normalizeType(type),
                limit
        );
    }

    public List<MapStoreResponse> findInMap(
            double swLat,
            double swLng,
            double neLat,
            double neLng,
            String type
    ) {
        if (swLat >= neLat || swLng >= neLng) {
            throw new GlobalException(
                    ErrorCode.INVALID_PARAMETER,
                    "남서쪽 좌표는 북동쪽 좌표보다 작아야 합니다."
            );
        }

        return storeRepository.findInMap(swLat, swLng, neLat, neLng, normalizeType(type));
    }

    private String normalizeType(String type) {
        return type == null || type.isBlank() ? null : type.trim();
    }
}
