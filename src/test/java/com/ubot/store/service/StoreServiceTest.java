package com.ubot.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ubot.common.ErrorCode;
import com.ubot.common.PageResponseDto;
import com.ubot.store.dto.MapClusterResponseDto;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreListResponseDto;
import com.ubot.store.exception.InvalidMapBoundsException;
import com.ubot.store.exception.ServiceTypeNotFoundException;
import com.ubot.store.exception.StoreNotFoundException;
import com.ubot.store.repository.StoreRepository;

@DisplayName("매장 Service 테스트")
class StoreServiceTest {

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final StoreService storeService = new StoreService(storeRepository);

    @Test
    @DisplayName("매장 조회 조건의 앞뒤 공백을 제거한다")
    void normalizesStoreSearchConditions() {
        when(storeRepository.countActiveServiceTypes(List.of("APPLE_AS"))).thenReturn(1L);
        when(storeRepository.findStores("서울특별시", "강남구", List.of("APPLE_AS"), 0, 20))
                .thenReturn(List.of());
        when(storeRepository.countStores("서울특별시", "강남구", List.of("APPLE_AS")))
                .thenReturn(0L);

        PageResponseDto<StoreListResponseDto> result = storeService.getStoreList(
                " 서울특별시 ",
                " 강남구 ",
                List.of(" APPLE_AS ", "APPLE_AS"),
                0,
                20
        );

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
        verify(storeRepository).countActiveServiceTypes(List.of("APPLE_AS"));
        verify(storeRepository).findStores("서울특별시", "강남구", List.of("APPLE_AS"), 0, 20);
    }

    @Test
    @DisplayName("존재하지 않는 서비스 유형이면 예외가 발생한다")
    void rejectsUnknownServiceType() {
        when(storeRepository.countActiveServiceTypes(List.of("UNKNOWN_SERVICE"))).thenReturn(0L);

        assertThatThrownBy(() -> storeService.getStoreList(
                null, null, List.of("UNKNOWN_SERVICE"), 0, 20
        ))
                .isInstanceOf(ServiceTypeNotFoundException.class)
                .extracting(exception -> ((ServiceTypeNotFoundException) exception).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_TYPE_NOT_FOUND);

        verify(storeRepository, never()).findStores(
                null, null, List.of("UNKNOWN_SERVICE"), 0, 20
        );
    }

    @Test
    @DisplayName("반경을 미터로 변환하고 서비스 유형의 앞뒤 공백을 제거한다")
    void convertsRadiusToMetersAndNormalizesType() {
        when(storeRepository.countActiveServiceTypes(List.of("APPLE_AS"))).thenReturn(1L);
        when(storeRepository.findNearby(37.5, 127.0, 10_000.0, List.of("APPLE_AS"), 5))
                .thenReturn(List.of());

        List<NearbyStoreResponseDto> result = storeService.getNearbyStoreList(
                37.5,
                127.0,
                10.0,
                List.of(" APPLE_AS "),
                5
        );

        assertThat(result).isEmpty();
        verify(storeRepository).findNearby(37.5, 127.0, 10_000.0, List.of("APPLE_AS"), 5);
    }

    @Test
    @DisplayName("지도 영역 좌표가 올바르지 않으면 예외가 발생한다")
    void rejectsInvalidMapBounds() {
        assertThatThrownBy(() -> storeService.getMapStoreList(
                37.52,
                127.00,
                37.48,
                127.05,
                List.of()
        ))
                .isInstanceOf(InvalidMapBoundsException.class)
                .extracting(exception -> ((InvalidMapBoundsException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_MAP_BOUNDS);

        verifyNoInteractions(storeRepository);
    }

    @ParameterizedTest(name = "지도 레벨 {0}은 {1}m 격자를 사용한다")
    @CsvSource({
            "9, 5000",
            "10, 10000",
            "11, 25000",
            "12, 50000",
            "13, 100000"
    })
    @DisplayName("지도 레벨에 맞는 격자 크기로 클러스터를 조회한다")
    void getsMapClustersWithGridSizeForLevel(int level, double gridMeters) {
        List<MapClusterResponseDto> clusters = List.of(
                new MapClusterResponseDto(37.5, 127.0, 12)
        );
        when(storeRepository.findClusters(
                37.0, 126.0, 38.0, 128.0, gridMeters, List.of()
        )).thenReturn(clusters);

        assertThat(storeService.getMapClusterList(
                37.0, 126.0, 38.0, 128.0, level, List.of()
        )).isSameAs(clusters);
        verify(storeRepository).findClusters(
                37.0, 126.0, 38.0, 128.0, gridMeters, List.of()
        );
    }

    @Test
    @DisplayName("매장이 존재하지 않으면 예외가 발생한다")
    void throwsNotFoundWhenStoreDoesNotExist() {
        when(storeRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storeService.getStore(999L))
                .isInstanceOf(StoreNotFoundException.class)
                .extracting(exception -> ((StoreNotFoundException) exception).getErrorCode())
                .isEqualTo(ErrorCode.STORE_NOT_FOUND);
    }

    @Test
    @DisplayName("매장이 존재하면 상세 정보를 반환한다")
    void returnsStoreDetailWhenStoreExists() {
        StoreDetailResponseDto detail = new StoreDetailResponseDto(
                1L,
                "강남역점",
                "서울특별시",
                "강남구",
                "서울특별시 강남구 강남대로 396",
                "02-0000-0000",
                null,
                37.498,
                127.028,
                List.of()
        );
        when(storeRepository.findById(1L)).thenReturn(Optional.of(detail));

        assertThat(storeService.getStore(1L)).isSameAs(detail);
    }

    @Test
    @DisplayName("시도 목록을 조회한다")
    void getsSidoList() {
        List<String> sidos = List.of("부산광역시", "서울특별시");
        when(storeRepository.findSidos()).thenReturn(sidos);

        assertThat(storeService.getSidoList()).isSameAs(sidos);
        verify(storeRepository).findSidos();
    }

    @Test
    @DisplayName("시도의 앞뒤 공백을 제거하고 시군구 목록을 조회한다")
    void getsSigunguList() {
        List<String> sigungus = List.of("강남구", "서초구");
        when(storeRepository.findSigungus("서울특별시")).thenReturn(sigungus);

        assertThat(storeService.getSigunguList(" 서울특별시 ")).isSameAs(sigungus);
        verify(storeRepository).findSigungus("서울특별시");
    }
}
