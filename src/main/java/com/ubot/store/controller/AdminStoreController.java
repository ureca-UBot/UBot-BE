package com.ubot.store.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ubot.common.ApiResponse;
import com.ubot.store.dto.request.AdminStoreCreateRequestDto;
import com.ubot.store.dto.request.AdminStoreUpdateRequestDto;
import com.ubot.store.dto.response.AdminStoreResponseDto;
import com.ubot.store.service.AdminStoreService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/admin/stores")
@RequiredArgsConstructor
@Validated
public class AdminStoreController {

    private final AdminStoreService adminStoreService;

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
}
