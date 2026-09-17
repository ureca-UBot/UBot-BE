package com.ubot.store.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.ubot.store.dto.MapStoreResponseDto;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreListResponseDto;
import com.ubot.store.exception.InvalidMapBoundsException;
import com.ubot.store.exception.StoreNotFoundException;
import com.ubot.store.repository.StoreRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;

    public List<StoreListResponseDto> getStoreList(String sido, String sigungu, String type) {
        return storeRepository.findStores(
                normalizeCondition(sido),
                normalizeCondition(sigungu),
                normalizeCondition(type)
        );
    }

    public StoreDetailResponseDto getStore(long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(StoreNotFoundException::new);
    }

    public List<NearbyStoreResponseDto> getNearbyStoreList(
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

    public List<MapStoreResponseDto> getMapStoreList(
            double swLat,
            double swLng,
            double neLat,
            double neLng,
        String type
    ) {
        if (swLat >= neLat || swLng >= neLng) {
            throw new InvalidMapBoundsException();
        }

        return storeRepository.findInMap(swLat, swLng, neLat, neLng, normalizeCondition(type));
    }

    private String normalizeCondition(String condition) {
        return condition == null || condition.isBlank() ? null : condition.trim();
    }
}
