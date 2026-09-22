package com.ubot.direction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ubot.common.ErrorCode;
import com.ubot.direction.client.KakaoDirectionsClient;
import com.ubot.direction.dto.DirectionsMode;
import com.ubot.direction.dto.DirectionsResponseDto;
import com.ubot.direction.dto.DirectionsResponseDto.PointDto;
import com.ubot.direction.dto.DirectionsResponseDto.StepDto;
import com.ubot.direction.exception.DirectionsException;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.exception.StoreNotFoundException;
import com.ubot.store.service.StoreService;

@DisplayName("길찾기 Service 테스트")
class DirectionsServiceTest {

    private final StoreService storeService = mock(StoreService.class);
    private final KakaoDirectionsClient kakaoDirectionsClient = mock(KakaoDirectionsClient.class);
    private final DirectionsService directionsService =
            new DirectionsService(storeService, kakaoDirectionsClient);

    @Test
    @DisplayName("매장이 없으면 카카오를 호출하지 않고 예외가 발생한다")
    void doesNotCallKakaoWhenStoreDoesNotExist() {
        when(storeService.getStore(999L)).thenThrow(new StoreNotFoundException());

        assertThatThrownBy(() ->
                directionsService.getStoreDirections(999L, DirectionsMode.WALK, 37.5, 127.0))
                .isInstanceOf(StoreNotFoundException.class);

        verifyNoInteractions(kakaoDirectionsClient);
    }

    @Test
    @DisplayName("도보 모드는 카카오를 한 번만 호출하고 결과 1건을 반환한다")
    void passesWalkResultThroughWithoutExtraCalls() {
        StoreDetailResponseDto store = new StoreDetailResponseDto(
                1L, "강남역점", "서울특별시", "강남구", "서울특별시 강남구 강남대로 396",
                "02-0000-0000", null, 37.498, 127.028, null, List.of()
        );
        DirectionsResponseDto expected = new DirectionsResponseDto(
                DirectionsMode.WALK, 1000, 900, List.of(), List.of()
        );
        when(storeService.getStore(1L)).thenReturn(store);
        when(kakaoDirectionsClient.walk(new PointDto(37.5, 127.0), new PointDto(37.498, 127.028)))
                .thenReturn(expected);

        assertThat(directionsService.getStoreDirections(1L, DirectionsMode.WALK, 37.5, 127.0))
                .containsExactly(expected);

        verify(kakaoDirectionsClient, times(1))
                .walk(new PointDto(37.5, 127.0), new PointDto(37.498, 127.028));
    }

    @Test
    @DisplayName("대중교통 목록은 카카오 원본 소요 시간이 짧은 순으로 정렬한 후보를 그대로 반환하고 도보는 부르지 않는다")
    void listsTransitCandidatesSortedByDurationWithoutCallingWalk() {
        StoreDetailResponseDto store = new StoreDetailResponseDto(
                1L, "강남역점", "서울특별시", "강남구", "서울특별시 강남구 강남대로 396",
                "02-0000-0000", null, 37.4, 127.1, null, List.of()
        );
        PointDto origin = new PointDto(37.5, 127.0);
        PointDto destination = new PointDto(37.4, 127.1);

        // 카카오가 준 순서(느린 순, 1620초)와 실제 소요 시간 순서(900초가 더 짧음)가 다르다.
        DirectionsResponseDto slower = new DirectionsResponseDto(
                DirectionsMode.TRANSIT, 4800, 1620, List.of(), List.of()
        );
        DirectionsResponseDto faster = new DirectionsResponseDto(
                DirectionsMode.TRANSIT, 6000, 900, List.of(), List.of()
        );

        when(storeService.getStore(1L)).thenReturn(store);
        when(kakaoDirectionsClient.getTransitRoutes(origin, destination))
                .thenReturn(List.of(slower, faster));

        List<DirectionsResponseDto> results =
                directionsService.getStoreDirections(1L, DirectionsMode.TRANSIT, 37.5, 127.0);

        // 정렬만 하고 도보로 채우지 않으므로, 카카오가 준 원본 객체가 그대로(같은 순서 재배열만 되어) 온다.
        assertThat(results).containsExactly(faster, slower);
        verify(kakaoDirectionsClient, never()).walk(any(), any());
    }

    @Test
    @DisplayName("대중교통 후보는 개수를 자르지 않고 전부 소요 시간이 짧은 순으로 반환한다")
    void listsAllTransitCandidatesSortedByDuration() {
        StoreDetailResponseDto store = new StoreDetailResponseDto(
                1L, "강남역점", "서울특별시", "강남구", "서울특별시 강남구 강남대로 396",
                "02-0000-0000", null, 37.4, 127.1, null, List.of()
        );
        PointDto origin = new PointDto(37.5, 127.0);
        PointDto destination = new PointDto(37.4, 127.1);

        DirectionsResponseDto routeA = transitCandidate(3000);
        DirectionsResponseDto routeB = transitCandidate(1000);
        DirectionsResponseDto routeC = transitCandidate(2000);
        DirectionsResponseDto routeD = transitCandidate(4000);
        DirectionsResponseDto routeE = transitCandidate(5000);

        when(storeService.getStore(1L)).thenReturn(store);
        when(kakaoDirectionsClient.getTransitRoutes(origin, destination))
                .thenReturn(List.of(routeA, routeB, routeC, routeD, routeE));

        List<DirectionsResponseDto> results =
                directionsService.getStoreDirections(1L, DirectionsMode.TRANSIT, 37.5, 127.0);

        // 5개(카카오가 준 개수) 전부, 짧은 순으로 반환한다.
        assertThat(results).extracting(DirectionsResponseDto::durationSeconds)
                .containsExactly(1000, 2000, 3000, 4000, 5000);
    }

    private DirectionsResponseDto transitCandidate(int durationSeconds) {
        return new DirectionsResponseDto(
                DirectionsMode.TRANSIT, durationSeconds, durationSeconds, List.of(), List.of()
        );
    }

    @Test
    @DisplayName("선택한 대중교통 후보에 앞뒤 도보를 채워 반환한다")
    void fillsWalkingLegsForSelectedTransitCandidate() {
        StoreDetailResponseDto store = new StoreDetailResponseDto(
                1L, "강남역점", "서울특별시", "강남구", "서울특별시 강남구 강남대로 396",
                "02-0000-0000", null, 37.4, 127.1, null, List.of()
        );
        PointDto origin = new PointDto(37.5, 127.0);
        PointDto destination = new PointDto(37.4, 127.1);
        PointDto transitFirst = new PointDto(37.498, 127.005);
        PointDto transitMid = new PointDto(37.49, 127.01);
        PointDto transitLast = new PointDto(37.402, 127.095);

        StepDto transitWalking = new StepDto(
                "WALKING", "정류장까지 도보", 200, 180, List.of(), List.of(),
                List.of(transitFirst, transitMid)
        );
        StepDto transitBus = new StepDto(
                "BUS", "버스 탑승", 4800, 1620, List.of("강남역", "역삼역"), List.of("146"),
                List.of(transitMid, transitLast)
        );
        DirectionsResponseDto candidate = new DirectionsResponseDto(
                DirectionsMode.TRANSIT, 5000, 1800,
                List.of(transitFirst, transitMid, transitMid, transitLast),
                List.of(transitWalking, transitBus)
        );
        DirectionsResponseDto leadingWalk = new DirectionsResponseDto(
                DirectionsMode.WALK, 150, 120, List.of(origin, transitFirst), List.of()
        );
        DirectionsResponseDto trailingWalk = new DirectionsResponseDto(
                DirectionsMode.WALK, 180, 140, List.of(transitLast, destination), List.of()
        );

        when(storeService.getStore(1L)).thenReturn(store);
        when(kakaoDirectionsClient.walk(origin, transitFirst)).thenReturn(leadingWalk);
        when(kakaoDirectionsClient.walk(transitLast, destination)).thenReturn(trailingWalk);

        DirectionsResponseDto result =
                directionsService.fillTransitWalkingLegs(1L, candidate, 37.5, 127.0);

        // 150(출발지→정류장 도보) + 200(카카오 내부 도보) + 4800(버스) + 180(하차 지점→목적지 도보)
        assertThat(result.distanceMeters()).isEqualTo(5330);
        assertThat(result.durationSeconds()).isEqualTo(2060);
        assertThat(result.steps()).hasSize(4);
        assertThat(result.steps().get(0).guidance()).isEqualTo("출발지에서 첫 정류장까지 도보");
        assertThat(result.steps().get(1)).isSameAs(transitWalking);
        assertThat(result.steps().get(2)).isSameAs(transitBus);
        assertThat(result.steps().get(3).guidance()).isEqualTo("마지막 하차 지점에서 목적지까지 도보");
        assertThat(result.path()).containsExactly(
                origin, transitFirst,
                transitFirst, transitMid,
                transitMid, transitLast,
                transitLast, destination
        );
        // 목록 조회에서 쓰는 대중교통 API는 다시 부르지 않는다.
        verify(kakaoDirectionsClient, never()).getTransitRoutes(any(), any());
    }

    @Test
    @DisplayName("앞쪽 도보 구간을 찾지 못하면 뒤쪽 도보는 조회하지 않고 전체 상세 조회가 실패한다")
    void failsWholeDetailWhenLeadingWalkLegIsNotFound() {
        StoreDetailResponseDto store = new StoreDetailResponseDto(
                1L, "강남역점", "서울특별시", "강남구", "서울특별시 강남구 강남대로 396",
                "02-0000-0000", null, 37.4, 127.1, null, List.of()
        );
        PointDto origin = new PointDto(37.5, 127.0);
        PointDto destination = new PointDto(37.4, 127.1);
        PointDto transitFirst = new PointDto(37.498, 127.005);
        PointDto transitLast = new PointDto(37.402, 127.095);

        StepDto transitBus = new StepDto(
                "BUS", "버스 탑승", 4800, 1620, List.of(), List.of(),
                List.of(transitFirst, transitLast)
        );
        DirectionsResponseDto candidate = new DirectionsResponseDto(
                DirectionsMode.TRANSIT, 5000, 1800,
                List.of(transitFirst, transitLast),
                List.of(transitBus)
        );

        when(storeService.getStore(1L)).thenReturn(store);
        when(kakaoDirectionsClient.walk(origin, transitFirst))
                .thenThrow(new DirectionsException(ErrorCode.DIRECTIONS_ROUTE_NOT_FOUND));

        assertThatThrownBy(() ->
                directionsService.fillTransitWalkingLegs(1L, candidate, 37.5, 127.0))
                .isInstanceOf(DirectionsException.class)
                .extracting(exception -> ((DirectionsException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DIRECTIONS_ROUTE_NOT_FOUND);

        // 앞쪽이 이미 실패했으므로 뒤쪽 도보는 아예 조회하지 않는다(쓸모없는 카카오 호출을 만들지 않는다).
        verify(kakaoDirectionsClient, never()).walk(transitLast, destination);
    }

    @Test
    @DisplayName("뒤쪽 도보 구간을 찾지 못해도 전체 상세 조회가 실패한다")
    void failsWholeDetailWhenTrailingWalkLegIsNotFound() {
        StoreDetailResponseDto store = new StoreDetailResponseDto(
                1L, "강남역점", "서울특별시", "강남구", "서울특별시 강남구 강남대로 396",
                "02-0000-0000", null, 37.4, 127.1, null, List.of()
        );
        PointDto origin = new PointDto(37.5, 127.0);
        PointDto destination = new PointDto(37.4, 127.1);
        PointDto transitFirst = new PointDto(37.498, 127.005);
        PointDto transitLast = new PointDto(37.402, 127.095);

        StepDto transitBus = new StepDto(
                "BUS", "버스 탑승", 4800, 1620, List.of(), List.of(),
                List.of(transitFirst, transitLast)
        );
        DirectionsResponseDto candidate = new DirectionsResponseDto(
                DirectionsMode.TRANSIT, 5000, 1800,
                List.of(transitFirst, transitLast),
                List.of(transitBus)
        );
        DirectionsResponseDto leadingWalk = new DirectionsResponseDto(
                DirectionsMode.WALK, 100, 90, List.of(origin, transitFirst), List.of()
        );

        when(storeService.getStore(1L)).thenReturn(store);
        when(kakaoDirectionsClient.walk(origin, transitFirst)).thenReturn(leadingWalk);
        when(kakaoDirectionsClient.walk(transitLast, destination))
                .thenThrow(new DirectionsException(ErrorCode.DIRECTIONS_ROUTE_NOT_FOUND));

        assertThatThrownBy(() ->
                directionsService.fillTransitWalkingLegs(1L, candidate, 37.5, 127.0))
                .isInstanceOf(DirectionsException.class)
                .extracting(exception -> ((DirectionsException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DIRECTIONS_ROUTE_NOT_FOUND);
    }

    @Test
    @DisplayName("후보에 좌표가 없으면 도보를 채우지 않고 그대로 반환한다")
    void returnsCandidateUnchangedWhenPathIsEmpty() {
        StoreDetailResponseDto store = new StoreDetailResponseDto(
                1L, "강남역점", "서울특별시", "강남구", "서울특별시 강남구 강남대로 396",
                "02-0000-0000", null, 37.4, 127.1, null, List.of()
        );
        DirectionsResponseDto candidate = new DirectionsResponseDto(
                DirectionsMode.TRANSIT, 5000, 1800, List.of(), List.of()
        );
        when(storeService.getStore(1L)).thenReturn(store);

        assertThat(directionsService.fillTransitWalkingLegs(1L, candidate, 37.5, 127.0))
                .isSameAs(candidate);

        verify(kakaoDirectionsClient, never()).walk(any(), any());
    }

    @Test
    @DisplayName("대중교통이 아닌 후보를 보내면 매장 조회조차 하지 않고 예외가 발생한다")
    void rejectsCandidateThatIsNotTransit() {
        DirectionsResponseDto walkCandidate = new DirectionsResponseDto(
                DirectionsMode.WALK, 1000, 900, List.of(), List.of()
        );

        assertThatThrownBy(() ->
                directionsService.fillTransitWalkingLegs(1L, walkCandidate, 37.5, 127.0))
                .isInstanceOf(DirectionsException.class)
                .extracting(exception -> ((DirectionsException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DIRECTIONS_INVALID_CANDIDATE);

        verifyNoInteractions(storeService);
        verifyNoInteractions(kakaoDirectionsClient);
    }
}
