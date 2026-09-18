package com.ubot.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.store.dto.MapStoreResponseDto;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreListResponseDto;

@SpringBootTest
@ActiveProfiles("test")
@Import(PgvectorTestConfiguration.class)
@Sql("/sql/store-repository-test-setup.sql")
@DisplayName("매장 Repository 통합 테스트")
class StoreRepositoryIntegrationTest {

    @Autowired
    private StoreRepository storeRepository;

    @Test
    @DisplayName("활성 서비스 유형의 존재 여부를 조회한다")
    void checksActiveServiceTypeExistence() {
        assertThat(storeRepository.existsActiveServiceType("APPLE_AS")).isTrue();
        assertThat(storeRepository.existsActiveServiceType("UNKNOWN_SERVICE")).isFalse();
    }

    @Test
    @DisplayName("지역과 서비스 유형으로 매장을 조회한다")
    void findsStoresByRegionAndServiceType() {
        List<StoreListResponseDto> activeStores = storeRepository.findStores(null, null, null);

        assertThat(activeStores)
                .extracting(StoreListResponseDto::storeId)
                .containsExactly(1L, 2L, 3L);

        List<StoreListResponseDto> gangnamStores = storeRepository.findStores(
                "서울특별시",
                "강남구",
                null
        );

        assertThat(gangnamStores)
                .extracting(StoreListResponseDto::storeId)
                .containsExactly(1L, 2L);

        List<StoreListResponseDto> appleStores = storeRepository.findStores(
                "서울특별시",
                "강남구",
                "APPLE_AS"
        );

        assertThat(appleStores)
                .extracting(StoreListResponseDto::storeId)
                .containsExactly(1L);
    }

    @Test
    @DisplayName("주변 매장을 거리순으로 조회하고 서비스 유형으로 필터링한다")
    void findsNearbyStoresInDistanceOrderAndFiltersByType() {
        List<NearbyStoreResponseDto> nearby = storeRepository.findNearby(
                37.4987,
                127.0286,
                1_000,
                null,
                10
        );

        assertThat(nearby)
                .extracting(NearbyStoreResponseDto::storeId)
                .containsExactly(1L, 2L);
        assertThat(nearby)
                .extracting(NearbyStoreResponseDto::distanceKm)
                .isSorted();

        List<NearbyStoreResponseDto> appleStores = storeRepository.findNearby(
                37.4987,
                127.0286,
                1_000,
                "APPLE_AS",
                10
        );

        assertThat(appleStores)
                .extracting(NearbyStoreResponseDto::storeId)
                .containsExactly(1L);
    }

    @Test
    @DisplayName("지도 영역 안의 매장만 조회한다")
    void findsOnlyStoresInsideMapBounds() {
        List<MapStoreResponseDto> stores = storeRepository.findInMap(
                37.49,
                127.02,
                37.51,
                127.04,
                null
        );

        assertThat(stores)
                .extracting(MapStoreResponseDto::storeId)
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("지원 서비스를 포함한 매장 상세 정보를 조회한다")
    void findsStoreDetailWithSupportedServices() {
        StoreDetailResponseDto detail = storeRepository.findById(1L).orElseThrow();

        assertThat(detail.storeName()).isEqualTo("강남역점");
        assertThat(detail.services())
                .extracting(StoreDetailResponseDto.ServiceResponseDto::code)
                .containsExactly("APPLE_AS");
        assertThat(storeRepository.findById(999L)).isEmpty();
    }
}
