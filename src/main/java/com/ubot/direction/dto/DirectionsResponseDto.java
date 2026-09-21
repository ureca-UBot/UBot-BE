package com.ubot.direction.dto;

import java.util.List;

/**
 * 이동수단과 관계없이 같은 형태로 내려주는 길찾기 결과입니다.
 *
 * @param path 출발지에서 목적지까지 이어지는 전체 경로 좌표. 지도에 선으로 그릴 때 사용합니다.
 * @param steps 구간별 안내. 대중교통에서만 채워지며 나머지 이동수단에서는 빈 목록입니다.
 */
public record DirectionsResponseDto(
        DirectionsMode mode,
        int distanceMeters,
        int durationSeconds,
        List<PointDto> path,
        List<StepDto> steps
) {

    public record PointDto(
            double latitude,
            double longitude
    ) {}

    /**
     * @param type {@code WALKING}, {@code BUS}, {@code SUBWAY} 중 하나입니다.
     * @param stops 이 구간에서 지나는 정류장·역 이름
     * @param vehicles 이 구간에서 탈 수 있는 노선 이름(버스 번호, 지하철 호선 등)
     */
    public record StepDto(
            String type,
            String guidance,
            int distanceMeters,
            int durationSeconds,
            List<String> stops,
            List<String> vehicles,
            List<PointDto> path
    ) {}
}
