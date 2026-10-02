package com.ubot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.location.dto.LocationSearchResponse;
import com.ubot.location.service.LocationService;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.service.StoreService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class StoreToolsTest {

    @Mock
    private StoreService storeService;

    @Mock
    private LocationService locationService;

    @InjectMocks
    private StoreTools storeTools;

    private final NearbyStoreResponseDto store = new NearbyStoreResponseDto(12L, "강남점", "서울", "강남구",
            "서울 강남구 테헤란로 1", "02-123-4567", "10:00~21:00", 37.49, 127.02, 0.42);

    @Test
    void usesPlaceMentionedInQuestion() {
        when(locationService.search("강남역")).thenReturn(List.of(
                new LocationSearchResponse("강남역", "서울 강남구", "서울 강남구 강남대로", 37.4979, 127.0276)));
        when(storeService.getNearbyStoreList(37.4979, 127.0276, 3.0, List.of(), 5)).thenReturn(List.of(store));

        // 공백을 제거하고 비교하므로 질문의 "강남 역"도 같은 장소로 봅니다.
        String result = storeTools.findNearbyStores("강남역", context(Map.of(
                StoreTools.QUESTION, "강남 역 근처 매장 알려줘",
                StoreTools.LATITUDE, 35.0,
                StoreTools.LONGITUDE, 129.0)));

        assertThat(result).isEqualTo(
                "[매장 ID: 12] 강남점 | 서울 강남구 테헤란로 1 | 02-123-4567 | 영업시간 10:00~21:00 | 0.4km"
                        + "\n\n" + StoreTools.MAP_NOTICE);
    }

    @Test
    void recordsPlaceBasedResultForMap() {
        when(locationService.search("강남역")).thenReturn(List.of(
                new LocationSearchResponse("강남역", "서울 강남구", "서울 강남구 강남대로", 37.4979, 127.0276)));
        when(storeService.getNearbyStoreList(37.4979, 127.0276, 3.0, List.of(), 5)).thenReturn(List.of(store));
        var recorder = new StoreSearchRecorder();

        storeTools.findNearbyStores(" 강남역 ", context(Map.of(
                StoreTools.QUESTION, "강남역 근처 매장",
                StoreTools.RECORDER, recorder)));

        assertThat(recorder.result()).contains(
                new StoreMapResult(new Location(37.4979, 127.0276), "강남역", 3.0, List.of(store)));
    }

    @Test
    void recordsRequestLocationResultWithoutPlaceName() {
        when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of(store));
        var recorder = new StoreSearchRecorder();

        storeTools.findNearbyStores(null, context(Map.of(
                StoreTools.QUESTION, "근처 매장",
                StoreTools.LATITUDE, 37.5,
                StoreTools.LONGITUDE, 127.0,
                StoreTools.RECORDER, recorder)));

        assertThat(recorder.result()).hasValueSatisfying(result -> {
            assertThat(result.center()).isEqualTo(new Location(37.5, 127.0));
            assertThat(result.placeName()).isNull();
            assertThat(result.stores()).containsExactly(store);
        });
    }

    @Test
    void recordsEmptyResultSoScreenCanShowNoStore() {
        when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of());
        var recorder = new StoreSearchRecorder();

        String result = storeTools.findNearbyStores(null, context(Map.of(
                StoreTools.QUESTION, "근처 매장",
                StoreTools.LATITUDE, 37.5,
                StoreTools.LONGITUDE, 127.0,
                StoreTools.RECORDER, recorder)));

        assertThat(result).isEqualTo(StoreTools.NO_STORE_MESSAGE);
        assertThat(recorder.result()).hasValueSatisfying(value -> assertThat(value.stores()).isEmpty());
    }

    @Test
    void doesNotRecordWhenLocationIsUnknownOrLookupFails() {
        when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5))
                .thenThrow(new IllegalStateException("db down"));
        var recorder = new StoreSearchRecorder();

        storeTools.findNearbyStores(null, context(Map.of(StoreTools.QUESTION, "근처 매장", StoreTools.RECORDER, recorder)));
        storeTools.findNearbyStores(null, context(Map.of(
                StoreTools.QUESTION, "근처 매장",
                StoreTools.LATITUDE, 37.5,
                StoreTools.LONGITUDE, 127.0,
                StoreTools.RECORDER, recorder)));

        assertThat(recorder.result()).isEmpty();
    }

    @Test
    void comparesPlaceIgnoringTabsAndLineBreaks() {
        when(locationService.search("강남역")).thenReturn(List.of());
        when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of(store));

        storeTools.findNearbyStores("강남역", context(Map.of(
                StoreTools.QUESTION, "강남\t역\n근처",
                StoreTools.LATITUDE, 37.5,
                StoreTools.LONGITUDE, 127.0)));

        verify(locationService).search("강남역");
    }

    @Test
    void ignoresPlaceNotInQuestionAndUsesRequestLocation() {
        when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of(store));

        String result = storeTools.findNearbyStores("홍대입구역", context(Map.of(
                StoreTools.QUESTION, "근처 매장 알려줘",
                StoreTools.LATITUDE, 37.5,
                StoreTools.LONGITUDE, 127.0)));

        assertThat(result).startsWith("[매장 ID: 12] 강남점");
        verifyNoInteractions(locationService);
    }

    @Test
    void fallsBackToRequestLocationWhenPlaceIsNotFound() {
        when(locationService.search("강남역")).thenReturn(List.of());
        when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of(store));

        String result = storeTools.findNearbyStores("강남역", context(Map.of(
                StoreTools.QUESTION, "강남역 매장",
                StoreTools.LATITUDE, 37.5,
                StoreTools.LONGITUDE, 127.0)));

        assertThat(result).startsWith("[매장 ID: 12]");
    }

    @Test
    void asksForLocationWhenNothingIsKnown() {
        String result = storeTools.findNearbyStores(null, context(Map.of(StoreTools.QUESTION, "근처 매장 알려줘")));

        assertThat(result).isEqualTo(StoreTools.NO_LOCATION_MESSAGE);
        verifyNoInteractions(storeService, locationService);
    }

    @Test
    void reportsWhenNoStoreIsNearby() {
        when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5)).thenReturn(List.of());

        String result = storeTools.findNearbyStores(" ", context(Map.of(
                StoreTools.QUESTION, "근처 매장",
                StoreTools.LATITUDE, 37.5,
                StoreTools.LONGITUDE, 127.0)));

        assertThat(result).isEqualTo(StoreTools.NO_STORE_MESSAGE);
    }

    @Test
    void returnsMessageInsteadOfThrowingWhenLookupFails() {
        when(locationService.search(anyString())).thenThrow(new IllegalStateException("kakao down"));

        String result = storeTools.findNearbyStores("강남역", context(Map.of(StoreTools.QUESTION, "강남역 매장")));

        assertThat(result).isEqualTo(StoreTools.LOOKUP_FAILED_MESSAGE);
        verify(storeService, never()).getNearbyStoreList(anyDouble(), anyDouble(), anyDouble(), anyList(), anyInt());
    }

    @Test
    void returnsMessageWhenStoreQueryFails() {
        when(storeService.getNearbyStoreList(37.5, 127.0, 3.0, List.of(), 5))
                .thenThrow(new IllegalStateException("db down"));

        String result = storeTools.findNearbyStores(null, context(Map.of(
                StoreTools.QUESTION, "근처 매장",
                StoreTools.LATITUDE, 37.5,
                StoreTools.LONGITUDE, 127.0)));

        assertThat(result).isEqualTo(StoreTools.LOOKUP_FAILED_MESSAGE);
    }

    private ToolContext context(Map<String, Object> values) {
        return new ToolContext(values);
    }
}
