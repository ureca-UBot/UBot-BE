package com.ubot.store.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;
import com.ubot.store.dto.MapStoreResponse;
import com.ubot.store.dto.NearbyStoreResponse;
import com.ubot.store.dto.StoreDetailResponse;
import com.ubot.store.dto.StoreListResponse;
import com.ubot.store.repository.StoreRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;

    public List<StoreListResponse> findStores(String sido, String sigungu, String type) {
        return storeRepository.findStores(
                normalizeCondition(sido),
                normalizeCondition(sigungu),
                normalizeCondition(type)
        );
    }

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
                normalizeCondition(type),
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

        return storeRepository.findInMap(swLat, swLng, neLat, neLng, normalizeCondition(type));
    }

    private String normalizeCondition(String condition) {
        return condition == null || condition.isBlank() ? null : condition.trim();
    }
}
