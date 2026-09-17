package com.ubot.store.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ubot.common.ApiResponse;
import com.ubot.store.dto.MapStoreResponse;
import com.ubot.store.dto.NearbyStoreResponse;
import com.ubot.store.dto.StoreDetailResponse;
import com.ubot.store.dto.StoreListResponse;
import com.ubot.store.service.StoreService;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
@Validated
public class StoreController {

    private static final String SERVICE_TYPE_PATTERN = "[A-Z][A-Z0-9_]*";
    private static final String MIN_KOREA_LATITUDE = "33.0";
    private static final String MAX_KOREA_LATITUDE = "39.0";
    private static final String MIN_KOREA_LONGITUDE = "124.0";
    private static final String MAX_KOREA_LONGITUDE = "132.0";

    private final StoreService storeService;

    @GetMapping
    public ApiResponse<List<StoreListResponse>> getStores(
            @RequestParam(required = false) String sido,
            @RequestParam(required = false) String sigungu,
            @RequestParam(required = false)
            @Pattern(regexp = SERVICE_TYPE_PATTERN) String type
    ) {
        return ApiResponse.success(storeService.findStores(sido, sigungu, type));
    }

    @GetMapping("/{storeId}")
    public ApiResponse<StoreDetailResponse> getStore(
            @PathVariable @Positive long storeId
    ) {
        return ApiResponse.success(storeService.findById(storeId));
    }

    @GetMapping("/nearby")
    public ApiResponse<List<NearbyStoreResponse>> getNearbyStores(
            @RequestParam
            @DecimalMin(MIN_KOREA_LATITUDE) @DecimalMax(MAX_KOREA_LATITUDE) double latitude,
            @RequestParam
            @DecimalMin(MIN_KOREA_LONGITUDE) @DecimalMax(MAX_KOREA_LONGITUDE) double longitude,
            @RequestParam(defaultValue = "10")
            @DecimalMin("0.1") @DecimalMax("100.0") double radiusKm,
            @RequestParam(required = false)
            @Pattern(regexp = SERVICE_TYPE_PATTERN) String type,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return ApiResponse.success(
                storeService.findNearby(latitude, longitude, radiusKm, type, limit)
        );
    }

    @GetMapping("/map")
    public ApiResponse<List<MapStoreResponse>> getStoresInMap(
            @RequestParam
            @DecimalMin(MIN_KOREA_LATITUDE) @DecimalMax(MAX_KOREA_LATITUDE) double swLat,
            @RequestParam
            @DecimalMin(MIN_KOREA_LONGITUDE) @DecimalMax(MAX_KOREA_LONGITUDE) double swLng,
            @RequestParam
            @DecimalMin(MIN_KOREA_LATITUDE) @DecimalMax(MAX_KOREA_LATITUDE) double neLat,
            @RequestParam
            @DecimalMin(MIN_KOREA_LONGITUDE) @DecimalMax(MAX_KOREA_LONGITUDE) double neLng,
            @RequestParam(required = false)
            @Pattern(regexp = SERVICE_TYPE_PATTERN) String type
    ) {
        return ApiResponse.success(storeService.findInMap(swLat, swLng, neLat, neLng, type));
    }
}
