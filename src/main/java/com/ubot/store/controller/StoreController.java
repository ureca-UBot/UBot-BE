package com.ubot.store.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ubot.common.ApiResponse;
import com.ubot.common.PageResponseDto;
import com.ubot.store.dto.MapClusterResponseDto;
import com.ubot.store.dto.MapStoreResponseDto;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreListResponseDto;
import com.ubot.store.service.StoreService;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
@Validated
public class StoreController {

    private static final String SERVICE_TYPE_PATTERN = "\\s*[A-Z][A-Z0-9_]*\\s*";
    private static final String MIN_KOREA_LATITUDE = "33.0";
    private static final String MAX_KOREA_LATITUDE = "39.0";
    private static final String MIN_KOREA_LONGITUDE = "124.0";
    private static final String MAX_KOREA_LONGITUDE = "132.0";
    private static final int MAX_SERVICE_TYPE_COUNT = 10;

    private final StoreService storeService;

    @GetMapping
    public ApiResponse<PageResponseDto<StoreListResponseDto>> getStores(
            @RequestParam(required = false) String sido,
            @RequestParam(required = false) String sigungu,
            @RequestParam(required = false, name = "type")
            @Size(max = MAX_SERVICE_TYPE_COUNT)
            List<@Pattern(regexp = SERVICE_TYPE_PATTERN) String> types,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(storeService.getStoreList(sido, sigungu, types, page, size));
    }

    @GetMapping("/regions/sidos")
    public ApiResponse<List<String>> getSidoList() {
        return ApiResponse.success(storeService.getSidoList());
    }

    @GetMapping("/regions/sigungus")
    public ApiResponse<List<String>> getSigunguList(
            @RequestParam @NotBlank String sido
    ) {
        return ApiResponse.success(storeService.getSigunguList(sido));
    }

    @GetMapping("/{storeId}")
    public ApiResponse<StoreDetailResponseDto> getStore(
            @PathVariable @Positive long storeId
    ) {
        return ApiResponse.success(storeService.getStore(storeId));
    }

    @GetMapping("/nearby")
    public ApiResponse<List<NearbyStoreResponseDto>> getNearbyStores(
            @RequestParam
            @DecimalMin(MIN_KOREA_LATITUDE) @DecimalMax(MAX_KOREA_LATITUDE) double latitude,
            @RequestParam
            @DecimalMin(MIN_KOREA_LONGITUDE) @DecimalMax(MAX_KOREA_LONGITUDE) double longitude,
            @RequestParam(defaultValue = "10")
            @DecimalMin("0.1") @DecimalMax("100.0") double radiusKm,
            @RequestParam(required = false, name = "type")
            @Size(max = MAX_SERVICE_TYPE_COUNT)
            List<@Pattern(regexp = SERVICE_TYPE_PATTERN) String> types,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return ApiResponse.success(
                storeService.getNearbyStoreList(latitude, longitude, radiusKm, types, limit)
        );
    }

    @GetMapping("/map")
    public ApiResponse<List<MapStoreResponseDto>> getStoresInMap(
            @RequestParam
            @DecimalMin(MIN_KOREA_LATITUDE) @DecimalMax(MAX_KOREA_LATITUDE) double swLat,
            @RequestParam
            @DecimalMin(MIN_KOREA_LONGITUDE) @DecimalMax(MAX_KOREA_LONGITUDE) double swLng,
            @RequestParam
            @DecimalMin(MIN_KOREA_LATITUDE) @DecimalMax(MAX_KOREA_LATITUDE) double neLat,
            @RequestParam
            @DecimalMin(MIN_KOREA_LONGITUDE) @DecimalMax(MAX_KOREA_LONGITUDE) double neLng,
            @RequestParam(required = false, name = "type")
            @Size(max = MAX_SERVICE_TYPE_COUNT)
            List<@Pattern(regexp = SERVICE_TYPE_PATTERN) String> types
    ) {
        return ApiResponse.success(storeService.getMapStoreList(swLat, swLng, neLat, neLng, types));
    }

    @GetMapping("/map/clusters")
    public ApiResponse<List<MapClusterResponseDto>> getStoreClusters(
            @RequestParam
            @DecimalMin(MIN_KOREA_LATITUDE) @DecimalMax(MAX_KOREA_LATITUDE) double swLat,
            @RequestParam
            @DecimalMin(MIN_KOREA_LONGITUDE) @DecimalMax(MAX_KOREA_LONGITUDE) double swLng,
            @RequestParam
            @DecimalMin(MIN_KOREA_LATITUDE) @DecimalMax(MAX_KOREA_LATITUDE) double neLat,
            @RequestParam
            @DecimalMin(MIN_KOREA_LONGITUDE) @DecimalMax(MAX_KOREA_LONGITUDE) double neLng,
            @RequestParam @Min(9) @Max(13) int level,
            @RequestParam(required = false, name = "type")
            @Size(max = MAX_SERVICE_TYPE_COUNT)
            List<@Pattern(regexp = SERVICE_TYPE_PATTERN) String> types
    ) {
        return ApiResponse.success(
                storeService.getMapClusterList(swLat, swLng, neLat, neLng, level, types)
        );
    }
}
