package com.ubot.direction.service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.ubot.common.ErrorCode;
import com.ubot.direction.client.KakaoDirectionsClient;
import com.ubot.direction.dto.DirectionsMode;
import com.ubot.direction.dto.DirectionsResponseDto;
import com.ubot.direction.dto.DirectionsResponseDto.PointDto;
import com.ubot.direction.dto.DirectionsResponseDto.StepDto;
import com.ubot.direction.exception.DirectionsException;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.service.StoreService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DirectionsService {

    private final StoreService storeService;
    private final KakaoDirectionsClient kakaoDirectionsClient;

    /**
     * 현재 위치에서 매장까지의 경로 후보를 조회합니다. 도보·자동차는 결과가 하나뿐이라 목록에 1건만 담깁니다.
     * 대중교통은 카카오가 주는 후보 경로 전부를 예상 소요 시간이 짧은 순으로 정렬해 돌려주는데, 이 시간은
     * 카카오의 자체 추정치이고 앞뒤 도보 구간은 아직 채워지지 않은 상태입니다(도보 API를 부르지 않으므로
     * 목록 조회는 카카오 호출 1번으로 끝납니다). 사용자가 그중 하나를 고르면 {@link #fillTransitWalkingLegs}로
     * 실제 도보 경로를 채우세요. 카카오 정책상 결과를 저장하지 않고 매번 실시간으로 조회합니다.
     */
    public List<DirectionsResponseDto> getStoreDirections(
            long storeId,
            DirectionsMode mode,
            double latitude,
            double longitude
    ) {
        StoreDetailResponseDto store = storeService.getStore(storeId);
        PointDto origin = new PointDto(latitude, longitude);
        PointDto destination = new PointDto(store.latitude(), store.longitude());

        return switch (mode) {
            case WALK -> List.of(kakaoDirectionsClient.walk(origin, destination));
            case CAR -> List.of(kakaoDirectionsClient.car(origin, destination));
            case TRANSIT -> kakaoDirectionsClient.getTransitRoutes(origin, destination).stream()
                    .sorted(Comparator.comparingInt(DirectionsResponseDto::durationSeconds))
                    .toList();
        };
    }

    /**
     * {@link #getStoreDirections}가 돌려준 대중교통 후보 하나({@code candidate})를 그대로 받아, 출발지→첫
     * 정류장, 마지막 하차 지점→목적지 구간의 도보 경로를 채웁니다. 카카오 대중교통 응답에는 이 두 구간이
     * 빠져 있어서, 사용자가 후보를 선택했을 때만 도보 길찾기를 앞뒤로 한 번씩 더, 총 2회 호출합니다.
     * 총 거리·시간도 카카오가 주는 값 대신 (도보 두 구간 + 대중교통 구간) 실제 합으로 다시 계산합니다.
     */
    public DirectionsResponseDto fillTransitWalkingLegs(
            long storeId,
            DirectionsResponseDto candidate,
            double latitude,
            double longitude
    ) {
        if (candidate.mode() != DirectionsMode.TRANSIT) {
            throw new DirectionsException(ErrorCode.DIRECTIONS_INVALID_CANDIDATE);
        }

        StoreDetailResponseDto store = storeService.getStore(storeId);
        PointDto origin = new PointDto(latitude, longitude);
        PointDto destination = new PointDto(store.latitude(), store.longitude());

        return fillWalkingLegs(candidate, origin, destination);
    }

    /**
     * 출발지→첫 정류장, 하차 지점→목적지 두 도보 구간을 한 묶음으로 취급합니다. 둘 중 하나라도 실패하면
     * (예: 카카오가 SAME_POINT·경로 없음을 반환) 예외를 그대로 던져 전체 상세 조회를 실패시킵니다 —
     * 한쪽만 채워진 반쪽짜리 경로를 반환하지 않습니다.
     */
    private DirectionsResponseDto fillWalkingLegs(
            DirectionsResponseDto transit,
            PointDto origin,
            PointDto destination
    ) {
        if (transit.path().isEmpty()) {
            return transit;
        }

        StepDto leadingWalk = walkStep(origin, transit.path().getFirst(), "출발지에서 첫 정류장까지 도보");
        StepDto trailingWalk = walkStep(transit.path().getLast(), destination, "마지막 하차 지점에서 목적지까지 도보");

        List<StepDto> steps = Stream.of(List.of(leadingWalk), transit.steps(), List.of(trailingWalk))
                .flatMap(List::stream)
                .toList();
        List<PointDto> path = steps.stream()
                .flatMap(step -> step.path().stream())
                .toList();

        return new DirectionsResponseDto(
                DirectionsMode.TRANSIT,
                steps.stream().mapToInt(StepDto::distanceMeters).sum(),
                steps.stream().mapToInt(StepDto::durationSeconds).sum(),
                path,
                steps,
                transit.landingUrl(),
                transit.transitInfo(),
                null
        );
    }

    /** {@code from}에서 {@code to}까지의 도보 경로를 조회해 하나의 {@link StepDto}로 감쌉니다. */
    private StepDto walkStep(PointDto from, PointDto to, String guidance) {
        DirectionsResponseDto walk = kakaoDirectionsClient.walk(from, to);
        return new StepDto(
                "WALKING",
                guidance,
                walk.distanceMeters(),
                walk.durationSeconds(),
                List.of(),
                List.of(),
                walk.path()
        );
    }
}
