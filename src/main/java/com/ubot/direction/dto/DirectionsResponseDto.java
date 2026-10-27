package com.ubot.direction.dto;

import java.util.List;

/**
 * 이동수단과 관계없이 같은 형태로 내려주는 길찾기 결과입니다.
 *
 * @param path 출발지에서 목적지까지 이어지는 전체 경로 좌표. 지도에 선으로 그릴 때 사용합니다.
 * @param steps 구간별 안내. 대중교통·도보에서 채워지며, 자동차에서는 빈 목록입니다(자동차 턴바이턴
 *              안내는 {@code carInfo.guides}에 따로 담깁니다).
 * @param landingUrl 카카오맵 길찾기 결과 페이지 딥링크. 자동차는 카카오가 제공하지 않아 항상 {@code null}입니다.
 * @param transitInfo 대중교통일 때만 채워지는 요금·환승 정보. 다른 이동수단에서는 {@code null}입니다.
 * @param carInfo 자동차일 때만 채워지는 요금·턴바이턴 안내 정보. 다른 이동수단에서는 {@code null}입니다.
 */
public record DirectionsResponseDto(
        DirectionsMode mode,
        int distanceMeters,
        int durationSeconds,
        List<PointDto> path,
        List<StepDto> steps,
        String landingUrl,
        TransitInfo transitInfo,
        CarInfo carInfo
) {

    /** 대중교통·도보 API 없이도 필드 일부만으로 만들 수 있도록, 확장 필드는 모두 비운 채로 둡니다. */
    public DirectionsResponseDto(
            DirectionsMode mode,
            int distanceMeters,
            int durationSeconds,
            List<PointDto> path,
            List<StepDto> steps
    ) {
        this(mode, distanceMeters, durationSeconds, path, steps, null, null, null);
    }

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

    /**
     * @param fare 대중교통 요금. 정액 노선은 {@code value}만, 구간·환승 요금은 {@code min}/{@code max}만 채워집니다.
     * @param transfers 환승 횟수
     * @param type {@code BUS}, {@code SUBWAY}, {@code BUS_AND_SUBWAY} 중 하나입니다.
     */
    public record TransitInfo(
            TransitFare fare,
            int transfers,
            String type
    ) {}

    public record TransitFare(
            Integer value,
            Integer min,
            Integer max
    ) {}

    /** @param fare 예상 택시비(taxi)와 통행료(toll) */
    public record CarInfo(
            CarFare fare,
            List<CarGuideDto> guides
    ) {}

    public record CarFare(
            Integer taxi,
            Integer toll
    ) {}

    /** 자동차 경로의 턴바이턴 안내 지점 하나입니다. */
    public record CarGuideDto(
            String name,
            String guidance,
            int distanceMeters,
            int durationSeconds,
            PointDto point
    ) {}
}
