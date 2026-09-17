package com.ubot.location.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ubot.common.ApiResponse;
import com.ubot.location.dto.LocationSearchResponse;
import com.ubot.location.service.LocationService;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/locations")
@RequiredArgsConstructor
@Validated
public class LocationController {
    private final LocationService locationService;

    @GetMapping("/search")
    public ApiResponse<List<LocationSearchResponse>> search(@RequestParam @NotBlank String query) {
        return ApiResponse.success(locationService.search(query));
    }
}
