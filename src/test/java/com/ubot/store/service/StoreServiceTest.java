package com.ubot.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ubot.common.ErrorCode;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreListResponseDto;
import com.ubot.store.exception.InvalidMapBoundsException;
import com.ubot.store.exception.StoreNotFoundException;
import com.ubot.store.repository.StoreRepository;

class StoreServiceTest {

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final StoreService storeService = new StoreService(storeRepository);

    @Test
    void normalizesStoreSearchConditions() {
        when(storeRepository.findStores("서울특별시", "강남구", "APPLE_AS"))
                .thenReturn(List.of());

        List<StoreListResponseDto> result = storeService.getStoreList(
                " 서울특별시 ",
                " 강남구 ",
                " APPLE_AS "
        );

        assertThat(result).isEmpty();
        verify(storeRepository).findStores("서울특별시", "강남구", "APPLE_AS");
    }

    @Test
    void convertsRadiusToMetersAndNormalizesType() {
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
    void throwsNotFoundWhenStoreDoesNotExist() {
        when(storeRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storeService.getStore(999L))
                .isInstanceOf(StoreNotFoundException.class)
                .extracting(exception -> ((StoreNotFoundException) exception).getErrorCode())
                .isEqualTo(ErrorCode.STORE_NOT_FOUND);
    }

    @Test
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
