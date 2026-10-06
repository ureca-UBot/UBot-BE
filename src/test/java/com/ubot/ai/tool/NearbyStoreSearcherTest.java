package com.ubot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.service.StoreService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NearbyStoreSearcherTest {

    @Mock
    private StoreService storeService;

    private NearbyStoreSearcher nearbyStoreSearcher;

    private final Location center = new Location(37.5, 127.0);
    private final NearbyStoreResponseDto store = new NearbyStoreResponseDto(12L, "강남점", "서울", "강남구",
            "서울 강남구 테헤란로 1", "02-123-4567", "10:00~21:00", 37.49, 127.02, 0.42);

    @BeforeEach
    void setUp() {
        nearbyStoreSearcher = new NearbyStoreSearcher(storeService, 3.0, 5);
    }

    @Test
    void searchesWithinFixedRadiusAndLimit() {
        when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of(store));

        StoreMapResult result = nearbyStoreSearcher.search(center, "강남역");

        assertThat(result).isEqualTo(new StoreMapResult(center, "강남역", 3.0, List.of(store)));
    }

    @Test
    void keepsPlaceNameNullForCurrentLocation() {
        when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of());

        assertThat(nearbyStoreSearcher.search(center, null).placeName()).isNull();
    }

    @Test
    void formatsStoresWithMapNotice() {
        var noContact = new NearbyStoreResponseDto(13L, "역삼점", "서울", "강남구",
                "서울 강남구 역삼로 2", null, " ", 37.50, 127.03, 1.25);

        String text = nearbyStoreSearcher.format(new StoreMapResult(center, null, 3.0, List.of(store, noContact)));

        assertThat(text).isEqualTo(
                "[매장 ID: 12] 강남점 | 서울 강남구 테헤란로 1 | 02-123-4567 | 영업시간 10:00~21:00 | 0.4km\n"
                        + "[매장 ID: 13] 역삼점 | 서울 강남구 역삼로 2 | 정보 없음 | 영업시간 정보 없음 | 1.3km"
                        + "\n\n" + NearbyStoreSearcher.MAP_NOTICE);
    }

    @Test
    void formatsEmptyResultAsNoStore() {
        String text = nearbyStoreSearcher.format(new StoreMapResult(center, null, 3.0, List.of()));

        assertThat(text).isEqualTo("반경 3km 안에 매장이 없습니다.");
    }

    @Test
    void usesConfiguredRadiusAndLimit() {
        var searcher = new NearbyStoreSearcher(storeService, 1.5, 10);
        when(storeService.getNearbyStoreList(37.5, 127.0, 1.5, List.of(), 10)).thenReturn(List.of());

        StoreMapResult result = searcher.search(center, null);

        assertThat(result.radiusKm()).isEqualTo(1.5);
        assertThat(searcher.format(result)).isEqualTo("반경 1.5km 안에 매장이 없습니다.");
    }

    @Test
    void rejectsInvalidPolicyValuesAtStartup() {
        assertThatThrownBy(() -> new NearbyStoreSearcher(storeService, 0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NearbyStoreSearcher(storeService, 3.0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NearbyStoreSearcher(storeService, 3.0, 101)).isInstanceOf(IllegalArgumentException.class);
    }
}
