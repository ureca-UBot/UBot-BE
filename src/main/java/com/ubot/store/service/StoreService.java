package com.ubot.store.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.ubot.store.dto.MapStoreResponseDto;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreListResponseDto;
import com.ubot.store.exception.InvalidMapBoundsException;
import com.ubot.store.exception.ServiceTypeNotFoundException;
import com.ubot.store.exception.StoreNotFoundException;
import com.ubot.store.repository.StoreRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;

    public List<StoreListResponseDto> getStoreList(String sido, String sigungu, String type) {
        String normalizedType = normalizeCondition(type);
        validateServiceType(normalizedType);

        return storeRepository.findStores(
                normalizeCondition(sido),
                normalizeCondition(sigungu),
                normalizedType
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
        String normalizedType = normalizeCondition(type);
        validateServiceType(normalizedType);

        return storeRepository.findNearby(
                latitude,
                longitude,
                radiusKm * 1_000,
                normalizedType,
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

        String normalizedType = normalizeCondition(type);
        validateServiceType(normalizedType);

        return storeRepository.findInMap(swLat, swLng, neLat, neLng, normalizedType);
    }

    private void validateServiceType(String type) {
        if (type != null && !storeRepository.existsActiveServiceType(type)) {
            throw new ServiceTypeNotFoundException();
        }
    }

    private String normalizeCondition(String condition) {
        return condition == null || condition.isBlank() ? null : condition.trim();
    }
}
