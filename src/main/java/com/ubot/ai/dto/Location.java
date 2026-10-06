package com.ubot.ai.dto;

/** 도구가 쓸 서버 측 위치입니다. LLM이 채우지 않습니다. */
public record Location(double latitude, double longitude) {

    public Location {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("위치 범위가 올바르지 않습니다.");
        }
    }
}
