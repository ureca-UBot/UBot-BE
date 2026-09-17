package com.myapp.location.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myapp.common.ApiResponse;
import com.myapp.location.dto.LocationSearchResponse;
import com.myapp.location.service.LocationService;

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
