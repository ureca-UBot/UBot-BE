package com.ubot.location.dto;

public record LocationSearchResponse(
        String name,
        String address,
        String roadAddress,
        double latitude,
        double longitude
) {}
