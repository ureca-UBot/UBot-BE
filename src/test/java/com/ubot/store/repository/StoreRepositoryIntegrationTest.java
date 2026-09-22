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
import com.ubot.store.dto.MapClusterResponseDto;
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
    @DisplayName("요청한 서비스 유형 중 활성 상태인 유형의 개수를 조회한다")
    void countsActiveServiceTypes() {
        assertThat(storeRepository.countActiveServiceTypes(
                List.of("APPLE_AS", "FOREIGN_LANGUAGE_SUPPORT")
        )).isEqualTo(2L);
        assertThat(storeRepository.countActiveServiceTypes(
                List.of("APPLE_AS", "UNKNOWN_SERVICE")
        )).isEqualTo(1L);
        assertThat(storeRepository.countActiveServiceTypes(
                List.of("APPLE_AS", "INACTIVE_SERVICE")
        )).isEqualTo(1L);
    }

    @Test
    @DisplayName("지역과 서비스 유형으로 매장을 조회한다")
    void findsStoresByRegionAndServiceType() {
        List<StoreListResponseDto> activeStores = storeRepository.findStores(
                null, null, List.of(), 0, 10
        );

        assertThat(activeStores)
                .extracting(StoreListResponseDto::storeId)
                .containsExactly(1L, 2L, 3L);

        List<StoreListResponseDto> gangnamStores = storeRepository.findStores(
                "서울특별시",
                "강남구",
                List.of(),
                0,
                10
        );

        assertThat(gangnamStores)
                .extracting(StoreListResponseDto::storeId)
                .containsExactly(1L, 2L);

        List<StoreListResponseDto> appleStores = storeRepository.findStores(
                "서울특별시",
                "강남구",
                List.of("APPLE_AS"),
                0,
                10
        );

        assertThat(appleStores)
                .extracting(StoreListResponseDto::storeId)
                .containsExactly(1L, 2L);

        List<StoreListResponseDto> multiServiceStores = storeRepository.findStores(
                "서울특별시",
                "강남구",
                List.of("APPLE_AS", "FOREIGN_LANGUAGE_SUPPORT"),
                0,
                10
        );

        assertThat(multiServiceStores)
                .extracting(StoreListResponseDto::storeId)
                .containsExactly(1L);
    }

    @Test
    @DisplayName("매장 목록을 페이지 단위로 조회하고 전체 개수를 반환한다")
    void findsStoresWithPagination() {
        assertThat(storeRepository.findStores(null, null, List.of(), 0, 2))
                .extracting(StoreListResponseDto::storeId)
                .containsExactly(1L, 2L);
        assertThat(storeRepository.findStores(null, null, List.of(), 1, 2))
                .extracting(StoreListResponseDto::storeId)
                .containsExactly(3L);
        assertThat(storeRepository.countStores(null, null, List.of()))
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("주변 매장을 거리순으로 조회하고 서비스 유형으로 필터링한다")
    void findsNearbyStoresInDistanceOrderAndFiltersByType() {
        List<NearbyStoreResponseDto> nearby = storeRepository.findNearby(
                37.4987,
                127.0286,
                1_000,
                List.of(),
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
                List.of("APPLE_AS"),
                10
        );

        assertThat(appleStores)
                .extracting(NearbyStoreResponseDto::storeId)
                .containsExactly(1L, 2L);

        List<NearbyStoreResponseDto> multiServiceStores = storeRepository.findNearby(
                37.4987,
                127.0286,
                1_000,
                List.of("APPLE_AS", "FOREIGN_LANGUAGE_SUPPORT"),
                10
        );

        assertThat(multiServiceStores)
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
                37.4987,
                127.0286,
                List.of()
        );

        assertThat(stores)
                .extracting(MapStoreResponseDto::storeId)
                .containsExactly(1L, 2L);
        assertThat(stores.getFirst().distanceKm()).isZero();
        assertThat(stores.get(1).distanceKm()).isPositive();

        List<MapStoreResponseDto> multiServiceStores = storeRepository.findInMap(
                37.49,
                127.02,
                37.51,
                127.04,
                37.4987,
                127.0286,
                List.of("APPLE_AS", "FOREIGN_LANGUAGE_SUPPORT")
        );

        assertThat(multiServiceStores)
                .extracting(MapStoreResponseDto::storeId)
                .containsExactly(1L);
    }

    @Test
    @DisplayName("지도 영역의 매장을 거리 기반으로 묶어 조회한다")
    void findsStoreClustersInsideMapBounds() {
        List<MapClusterResponseDto> clusters = storeRepository.findClusters(
                37.49,
                127.02,
                37.51,
                127.04,
                5_000,
                List.of()
        );

        assertThat(clusters).hasSize(1);
        MapClusterResponseDto cluster = clusters.getFirst();
        assertThat(cluster.count()).isEqualTo(2L);
        assertThat(cluster.latitude()).isBetween(37.4987, 37.5000);
        assertThat(cluster.longitude()).isBetween(127.0286, 127.0300);

        List<MapClusterResponseDto> fineRadiusClusters = storeRepository.findClusters(
                37.49,
                127.02,
                37.51,
                127.04,
                50,
                List.of()
        );
        assertThat(fineRadiusClusters).hasSize(2);

        List<MapClusterResponseDto> multiServiceClusters = storeRepository.findClusters(
                37.49,
                127.02,
                37.51,
                127.04,
                5_000,
                List.of("APPLE_AS", "FOREIGN_LANGUAGE_SUPPORT")
        );
        assertThat(multiServiceClusters).singleElement()
                .extracting(MapClusterResponseDto::count)
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("클러스터에는 활성 상태이고 삭제되지 않은 매장만 포함한다")
    void excludesInactiveAndDeletedStoresFromClusters() {
        List<MapClusterResponseDto> clusters = storeRepository.findClusters(
                33.0,
                124.0,
                39.0,
                132.0,
                5_000,
                List.of()
        );

        assertThat(clusters.stream().mapToLong(MapClusterResponseDto::count).sum())
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("지원 서비스를 포함한 매장 상세 정보를 조회한다")
    void findsStoreDetailWithSupportedServices() {
        StoreDetailResponseDto detail = storeRepository.findById(1L).orElseThrow();

        assertThat(detail.storeName()).isEqualTo("강남역점");
        assertThat(detail.services())
                .extracting(StoreDetailResponseDto.ServiceResponseDto::code)
                .containsExactly("APPLE_AS", "FOREIGN_LANGUAGE_SUPPORT");
        assertThat(storeRepository.findById(999L)).isEmpty();
    }

    @Test
    @DisplayName("활성 매장의 시도 목록을 중복 없이 조회한다")
    void findsDistinctSidos() {
        assertThat(storeRepository.findSidos())
                .containsExactly("서울특별시", "부산광역시");
        assertThat(storeRepository.findSidos())
                .doesNotContain("제주특별자치도", "대전광역시");
    }

    @Test
    @DisplayName("선택한 시도의 시군구 목록을 중복 없이 조회한다")
    void findsDistinctSigungusBySido() {
        assertThat(storeRepository.findSigungus("서울특별시"))
                .containsExactly("강남구");
        assertThat(storeRepository.findSigungus("존재하지 않는 시도"))
                .isEmpty();
        assertThat(storeRepository.findSigungus("제주특별자치도"))
                .isEmpty();
        assertThat(storeRepository.findSigungus("대전광역시"))
                .isEmpty();
    }
}
