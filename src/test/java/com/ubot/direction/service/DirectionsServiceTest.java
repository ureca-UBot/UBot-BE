package com.ubot.direction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ubot.direction.client.KakaoDirectionsClient;
import com.ubot.direction.dto.DirectionsMode;
import com.ubot.direction.dto.DirectionsResponseDto;
import com.ubot.direction.dto.DirectionsResponseDto.PointDto;
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
    @DisplayName("현재 위치를 출발지, 매장 좌표를 도착지로 경로를 조회한다")
    void requestsRouteFromCurrentLocationToStore() {
        StoreDetailResponseDto store = new StoreDetailResponseDto(
                1L, "강남역점", "서울특별시", "강남구", "서울특별시 강남구 강남대로 396",
                "02-0000-0000", null, 37.498, 127.028, null, List.of()
        );
        DirectionsResponseDto expected = new DirectionsResponseDto(
                DirectionsMode.TRANSIT, 5000, 1800, List.of(), List.of()
        );
        when(storeService.getStore(1L)).thenReturn(store);
        when(kakaoDirectionsClient.getDirections(
                DirectionsMode.TRANSIT,
                new PointDto(37.5, 127.0),
                new PointDto(37.498, 127.028)
        )).thenReturn(expected);

        assertThat(directionsService.getStoreDirections(1L, DirectionsMode.TRANSIT, 37.5, 127.0))
                .isSameAs(expected);
    }

    @Test
    @DisplayName("매장이 없으면 카카오를 호출하지 않고 예외가 발생한다")
    void doesNotCallKakaoWhenStoreDoesNotExist() {
        when(storeService.getStore(999L)).thenThrow(new StoreNotFoundException());

        assertThatThrownBy(() ->
                directionsService.getStoreDirections(999L, DirectionsMode.WALK, 37.5, 127.0))
                .isInstanceOf(StoreNotFoundException.class);

        verifyNoInteractions(kakaoDirectionsClient);
    }
}
