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

import com.ubot.common.ErrorCode;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreListResponseDto;
import com.ubot.store.exception.InvalidMapBoundsException;
import com.ubot.store.exception.ServiceTypeNotFoundException;
import com.ubot.store.exception.StoreNotFoundException;
import com.ubot.store.repository.StoreRepository;

@DisplayName("매장 서비스 테스트")
class StoreServiceTest {

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final StoreService storeService = new StoreService(storeRepository);

    @Test
    @DisplayName("매장 조회 조건의 앞뒤 공백을 제거한다")
    void normalizesStoreSearchConditions() {
        when(storeRepository.existsActiveServiceType("APPLE_AS")).thenReturn(true);
        when(storeRepository.findStores("서울특별시", "강남구", "APPLE_AS"))
                .thenReturn(List.of());

        List<StoreListResponseDto> result = storeService.getStoreList(
                " 서울특별시 ",
                " 강남구 ",
                " APPLE_AS "
        );

        assertThat(result).isEmpty();
        verify(storeRepository).existsActiveServiceType("APPLE_AS");
        verify(storeRepository).findStores("서울특별시", "강남구", "APPLE_AS");
    }

    @Test
    @DisplayName("존재하지 않는 서비스 유형이면 예외가 발생한다")
    void rejectsUnknownServiceType() {
        when(storeRepository.existsActiveServiceType("UNKNOWN_SERVICE")).thenReturn(false);

        assertThatThrownBy(() -> storeService.getStoreList(null, null, "UNKNOWN_SERVICE"))
                .isInstanceOf(ServiceTypeNotFoundException.class)
                .extracting(exception -> ((ServiceTypeNotFoundException) exception).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_TYPE_NOT_FOUND);

        verify(storeRepository, never()).findStores(null, null, "UNKNOWN_SERVICE");
    }

    @Test
    @DisplayName("반경을 미터로 변환하고 서비스 유형의 앞뒤 공백을 제거한다")
    void convertsRadiusToMetersAndNormalizesType() {
        when(storeRepository.existsActiveServiceType("APPLE_AS")).thenReturn(true);
        when(storeRepository.findNearby(37.5, 127.0, 10_000.0, "APPLE_AS", 5))
                .thenReturn(List.of());

        List<NearbyStoreResponseDto> result = storeService.getNearbyStoreList(
                37.5,
                127.0,
                10.0,
                " APPLE_AS ",
                5
        );

        assertThat(result).isEmpty();
        verify(storeRepository).findNearby(37.5, 127.0, 10_000.0, "APPLE_AS", 5);
    }

    @Test
    @DisplayName("지도 영역 좌표가 올바르지 않으면 예외가 발생한다")
    void rejectsInvalidMapBounds() {
        assertThatThrownBy(() -> storeService.getMapStoreList(
                37.52,
                127.00,
                37.48,
                127.05,
                null
        ))
                .isInstanceOf(InvalidMapBoundsException.class)
                .extracting(exception -> ((InvalidMapBoundsException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_MAP_BOUNDS);

        verifyNoInteractions(storeRepository);
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
}
