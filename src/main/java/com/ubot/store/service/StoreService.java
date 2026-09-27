package com.ubot.store.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.ubot.common.PageResponseDto;
import com.ubot.store.dto.MapClusterResponseDto;
import com.ubot.store.dto.MapStoreResponseDto;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreListResponseDto;
import com.ubot.store.exception.StoreErrorCode;
import com.ubot.store.exception.StoreException;
import com.ubot.store.repository.StoreRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;

    public PageResponseDto<StoreListResponseDto> getStoreList(
            String sido,
            String sigungu,
            List<String> types,
            int page,
            int size
    ) {
        List<String> normalizedTypes = normalizeTypes(types);
        validateServiceTypes(normalizedTypes);

        String normalizedSido = normalizeCondition(sido);
        String normalizedSigungu = normalizeCondition(sigungu);
        List<StoreListResponseDto> content = storeRepository.findStores(
                normalizedSido,
                normalizedSigungu,
                normalizedTypes,
                page,
                size
        );
        long totalElements = storeRepository.countStores(
                normalizedSido,
                normalizedSigungu,
                normalizedTypes
        );

        return PageResponseDto.of(content, page, size, totalElements);
    }

    public StoreDetailResponseDto getStore(long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
    }

    public List<String> getSidoList() {
        return storeRepository.findSidos();
    }

    public List<String> getSigunguList(String sido) {
        return storeRepository.findSigungus(normalizeCondition(sido));
    }

    public List<NearbyStoreResponseDto> getNearbyStoreList(
            double latitude,
            double longitude,
            double radiusKm,
            List<String> types,
            int limit
    ) {
        List<String> normalizedTypes = normalizeTypes(types);
        validateServiceTypes(normalizedTypes);

        return storeRepository.findNearby(
                latitude,
                longitude,
                radiusKm * 1_000,
                normalizedTypes,
                limit
        );
    }

    public List<MapStoreResponseDto> getMapStoreList(
            double swLat,
            double swLng,
            double neLat,
            double neLng,
            Double latitude,
            Double longitude,
            List<String> types
    ) {
        validateMapBounds(swLat, swLng, neLat, neLng);
        validateCoordinatePair(latitude, longitude);

        List<String> normalizedTypes = normalizeTypes(types);
        validateServiceTypes(normalizedTypes);

        return storeRepository.findInMap(
                swLat, swLng, neLat, neLng, latitude, longitude, normalizedTypes
        );
    }

    public List<MapClusterResponseDto> getMapClusterList(
            double swLat,
            double swLng,
            double neLat,
            double neLng,
            int level,
            List<String> types
    ) {
        validateMapBounds(swLat, swLng, neLat, neLng);

        List<String> normalizedTypes = normalizeTypes(types);
        validateServiceTypes(normalizedTypes);

        return storeRepository.findClusters(
                swLat,
                swLng,
                neLat,
                neLng,
                clusterRadiusMeters(level),
                normalizedTypes
        );
    }

    private double clusterRadiusMeters(int level) {
        return switch (level) {
            case 7 -> 600;
            case 8 -> 1_200;
            case 9 -> 2_000;
            case 10 -> 4_000;
            case 11 -> 8_000;
            case 12 -> 16_000;
            case 13 -> 32_000;
            default -> throw new IllegalArgumentException("지원하지 않는 지도 레벨입니다: " + level);
        };
    }

    private void validateMapBounds(double swLat, double swLng, double neLat, double neLng) {
        if (swLat >= neLat || swLng >= neLng) {
            throw new StoreException(StoreErrorCode.INVALID_MAP_BOUNDS);
        }
    }

    private void validateCoordinatePair(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new StoreException(StoreErrorCode.INVALID_STORE_COORDINATES);
        }
    }

    private void validateServiceTypes(List<String> types) {
        if (types.isEmpty()) {
            return;
        }

        long validTypeCount = storeRepository.countActiveServiceTypes(types);
        if (validTypeCount != types.size()) {
            throw new StoreException(StoreErrorCode.SERVICE_TYPE_NOT_FOUND);
        }
    }

    private List<String> normalizeTypes(List<String> types) {
        if (types == null) {
            return List.of();
        }
        return types.stream()
                .map(this::normalizeCondition)
                .filter(type -> type != null)
                .distinct()
                .toList();
    }

    private String normalizeCondition(String condition) {
        return condition == null || condition.isBlank() ? null : condition.trim();
    }
}
