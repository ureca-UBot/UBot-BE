package com.ubot.store.controller;

import java.net.URI;
import java.util.List;

import com.ubot.common.PageResponseDto;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.ubot.common.ApiResponse;
import com.ubot.store.dto.request.AdminStoreCreateRequestDto;
import com.ubot.store.dto.request.AdminStoreUpdateRequestDto;
import com.ubot.store.dto.response.AdminStoreResponseDto;
import com.ubot.store.service.AdminStoreService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/stores")
@RequiredArgsConstructor
@Validated
public class AdminStoreController {

    private final AdminStoreService adminStoreService;

    private static final String SERVICE_TYPE_PATTERN = "\\s*[A-Z][A-Z0-9_]*\\s*";
    private static final int MAX_SERVICE_TYPE_COUNT = 10;

    @PostMapping
    public ResponseEntity<ApiResponse<AdminStoreResponseDto>> createStore(
            @Valid @RequestBody AdminStoreCreateRequestDto request
    ) {
        AdminStoreResponseDto response = adminStoreService.createStore(request);
        return ResponseEntity.created(URI.create("/admin/stores/" + response.storeId()))
                .body(ApiResponse.success(response));
    }

    @PatchMapping("/{storeId}")
    public ResponseEntity<ApiResponse<AdminStoreResponseDto>> updateStore(
            @PathVariable @Positive Long storeId,
            @Valid @RequestBody AdminStoreUpdateRequestDto request
    ) {
        return ResponseEntity.ok(ApiResponse.success(adminStoreService.updateStore(storeId, request)));
    }

    @DeleteMapping("/{storeId}")
    public ResponseEntity<Void> deleteStore(
            @PathVariable @Positive Long storeId
    ) {
        adminStoreService.deleteStore(storeId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{storeId}/activate")
    public ResponseEntity<ApiResponse<AdminStoreResponseDto>> activateStore(
            @PathVariable @Positive Long storeId
    ){
        return ResponseEntity.ok(ApiResponse.success(adminStoreService.activateStore(storeId)));
    }

    @GetMapping
    public ApiResponse<PageResponseDto<AdminStoreResponseDto>> getStores(
            @RequestParam(required = false) @Size(max = 150)
            String storeName,

            @RequestParam(required = false) @Size(max = 30)
            String phoneNumber,

            @RequestParam(required = false) @Size(max = 50)
            String sido,

            @RequestParam(required = false) @Size(max = 50)
            String sigungu,

            @RequestParam(required = false, name = "type") @Size(max = MAX_SERVICE_TYPE_COUNT)
            List<@Pattern(regexp = SERVICE_TYPE_PATTERN) String> serviceCodes,

            @RequestParam(defaultValue = "0") @Min(0)
            int page,

            @RequestParam(defaultValue = "20") @Min(1) @Max(100)
            int size
    ) {
        return ApiResponse.success(
                adminStoreService.getStores(
                        storeName,
                        phoneNumber,
                        sido,
                        sigungu,
                        serviceCodes,
                        page,
                        size
                )
        );
    }
}
