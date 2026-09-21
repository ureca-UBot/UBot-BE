package com.ubot.store.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.ubot.common.PageResponseDto;
import com.ubot.store.dto.MapClusterResponseDto;
import com.ubot.store.dto.MapStoreResponseDto;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreListResponseDto;
import com.ubot.store.exception.InvalidMapBoundsException;
import com.ubot.store.exception.InvalidOriginException;
import com.ubot.store.exception.ServiceTypeNotFoundException;
import com.ubot.store.exception.StoreNotFoundException;
import com.ubot.store.repository.StoreRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;

    /**
     * 매장 목록을 조회합니다. 현재 위치({@code latitude}, {@code longitude})를 함께 주면 각 매장의 직선거리를 채웁니다.
     * 두 값은 함께 전달하거나 함께 생략해야 합니다.
     */
    public PageResponseDto<StoreListResponseDto> getStoreList(
            String sido,
            String sigungu,
            List<String> types,
            Double latitude,
            Double longitude,
            int page,
            int size
    ) {
        validateOrigin(latitude, longitude);

        List<String> normalizedTypes = normalizeTypes(types);
        validateServiceTypes(normalizedTypes);

        String normalizedSido = normalizeCondition(sido);
        String normalizedSigungu = normalizeCondition(sigungu);
        List<StoreListResponseDto> content = storeRepository.findStores(
                normalizedSido,
                normalizedSigungu,
                normalizedTypes,
                latitude,
                longitude,
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
        return getStore(storeId, null, null);
    }

    /**
     * 매장 상세를 조회합니다. 현재 위치({@code latitude}, {@code longitude})를 함께 주면 직선거리를 채웁니다.
     * 두 값은 함께 전달하거나 함께 생략해야 합니다.
     */
    public StoreDetailResponseDto getStore(long storeId, Double latitude, Double longitude) {
        validateOrigin(latitude, longitude);

        return storeRepository.findById(storeId, latitude, longitude)
                .orElseThrow(StoreNotFoundException::new);
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
            List<String> types
    ) {
        validateMapBounds(swLat, swLng, neLat, neLng);

        List<String> normalizedTypes = normalizeTypes(types);
        validateServiceTypes(normalizedTypes);

        return storeRepository.findInMap(swLat, swLng, neLat, neLng, normalizedTypes);
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
                clusterGridMeters(level),
                normalizedTypes
        );
    }

    private double clusterGridMeters(int level) {
        return switch (level) {
            case 9 -> 5_000;
            case 10 -> 10_000;
            case 11 -> 25_000;
            case 12 -> 50_000;
            default -> 100_000;
        };
    }

    private void validateOrigin(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new InvalidOriginException();
        }
    }

    private void validateMapBounds(double swLat, double swLng, double neLat, double neLng) {
        if (swLat >= neLat || swLng >= neLng) {
            throw new InvalidMapBoundsException();
        }
    }

    private void validateServiceTypes(List<String> types) {
        if (types.isEmpty()) {
            return;
        }

        long validTypeCount = storeRepository.countActiveServiceTypes(types);
        if (validTypeCount != types.size()) {
            throw new ServiceTypeNotFoundException();
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
