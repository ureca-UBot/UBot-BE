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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class StoreToolsTest {

    @Mock
    private StoreService storeService;

    @Mock
    private LocationService locationService;

    private StoreTools storeTools;

    private final StoreSearchRecorder recorder = new StoreSearchRecorder();
    private final LocationSearchResponse gangnam =
            new LocationSearchResponse("강남역", "서울 강남구", "서울 강남구 강남대로", 37.4979, 127.0276);
    private final NearbyStoreResponseDto store = new NearbyStoreResponseDto(12L, "강남점", "서울", "강남구",
            "서울 강남구 테헤란로 1", "02-123-4567", "10:00~21:00", 37.49, 127.02, 0.42);

    @BeforeEach
    void setUp() {
        // 반경·개수·문자열 형식은 실제 NearbyStoreSearcher로 확인합니다.
        storeTools = new StoreTools(new NearbyStoreSearcher(storeService, 3.0, 5), locationService);
    }

    @Test
    void usesPlaceMentionedInQuestion() {
        when(locationService.search("강남역")).thenReturn(List.of(gangnam));
        when(storeService.getNearbyStoreList(37.4979, 127.0276, 3.0, List.of(), 5)).thenReturn(List.of(store));

        // 공백을 제거하고 비교하므로 질문의 "강남 역"도 같은 장소로 봅니다.
        String result = storeTools.findNearbyStores("강남역", context("강남 역 근처 매장 알려줘"));

        assertThat(result).isEqualTo(
                "[매장 ID: 12] 강남점 | 서울 강남구 테헤란로 1 | 02-123-4567 | 영업시간 10:00~21:00 | 0.4km"
                        + "\n\n" + NearbyStoreSearcher.MAP_NOTICE);
    }

    @Test
    void recordsPlaceBasedResultForMap() {
        when(locationService.search("강남역")).thenReturn(List.of(gangnam));
        when(storeService.getNearbyStoreList(37.4979, 127.0276, 3.0, List.of(), 5)).thenReturn(List.of(store));

        storeTools.findNearbyStores(" 강남역 ", context("강남역 근처 매장"));

        assertThat(recorder.result()).contains(
                new StoreMapResult(new Location(37.4979, 127.0276), "강남역", 3.0, List.of(store)));
        assertThat(recorder.isLocationRequired()).isFalse();
    }

    @Test
    void stripsGenericWordsBeforeSearching() {
        when(locationService.search("강남역")).thenReturn(List.of(gangnam));
        when(storeService.getNearbyStoreList(37.4979, 127.0276, 3.0, List.of(), 5)).thenReturn(List.of(store));

        storeTools.findNearbyStores("강남역근처 대리점", context("강남역근처 대리점 찾아줘"));

        verify(locationService).search("강남역");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "근처", "내 위치", "근처 대리점", "우리 집 근처"})
    void marksLocationRequiredWhenNoPlaceIsGiven(String place) {
        String result = storeTools.findNearbyStores(place, context("근처 대리점 찾아줘"));

        assertThat(result).isEqualTo(StoreTools.NO_LOCATION_MESSAGE);
        assertThat(recorder.isLocationRequired()).isTrue();
        verifyNoInteractions(storeService, locationService);
    }

    @Test
    void asksToCallAgainWithoutMarkingWhenPlaceIsNotInQuestion() {
        // 사용자는 강남역을 말했는데 LLM이 다른 장소를 넣은 경우입니다. 내 위치를 요청하면 안 됩니다.
        String result = storeTools.findNearbyStores("역삼역", context("강남역 근처 매장 알려줘"));

        assertThat(result).isEqualTo(String.format(StoreTools.PLACE_NOT_IN_QUESTION_MESSAGE, "역삼역"));
        assertThat(recorder.isLocationRequired()).isFalse();
        verifyNoInteractions(storeService, locationService);
    }

    @Test
    void rejectsPlaceCorrectedByModel() {
        // 철자가 정확히 같아야 하므로, LLM이 사용자의 오타를 고쳐 넣으면 다시 부르게 합니다.
        String result = storeTools.findNearbyStores("역삼동", context("역샴동 근처 매장"));

        assertThat(result).isEqualTo(String.format(StoreTools.PLACE_NOT_IN_QUESTION_MESSAGE, "역삼동"));
        verifyNoInteractions(locationService);
    }

    @Test
    void asksUserAgainWithoutMarkingWhenPlaceIsNotFound() {
        when(locationService.search("역샴동")).thenReturn(List.of());

        String result = storeTools.findNearbyStores("역샴동", context("역샴동 근처 매장"));

        assertThat(result).isEqualTo(String.format(StoreTools.PLACE_NOT_FOUND_MESSAGE, "역샴동"));
        assertThat(recorder.isLocationRequired()).isFalse();
        assertThat(recorder.result()).isEmpty();
        verifyNoInteractions(storeService);
    }

    @Test
    void recordsEmptyResultSoScreenCanShowNoStore() {
        when(locationService.search("강남역")).thenReturn(List.of(gangnam));
        when(storeService.getNearbyStoreList(37.4979, 127.0276, 3.0, List.of(), 5)).thenReturn(List.of());

        String result = storeTools.findNearbyStores("강남역", context("강남역 매장"));

        assertThat(result).isEqualTo(NearbyStoreSearcher.noStoreMessage(3.0));
        assertThat(recorder.result()).hasValueSatisfying(value -> assertThat(value.stores()).isEmpty());
    }

    @Test
    void comparesPlaceIgnoringTabsAndLineBreaks() {
        when(locationService.search("강남역")).thenReturn(List.of());

        storeTools.findNearbyStores("강남역", context("강남\t역\n근처"));

        verify(locationService).search("강남역");
    }

    @Test
    void successfulSearchClearsEarlierLocationRequired() {
        when(locationService.search("강남역")).thenReturn(List.of(gangnam));
        when(storeService.getNearbyStoreList(37.4979, 127.0276, 3.0, List.of(), 5)).thenReturn(List.of(store));

        // LLM이 처음엔 장소 없이 부르고, 다시 장소를 넣어 부른 경우입니다.
        storeTools.findNearbyStores(null, context("강남역 근처 매장"));
        storeTools.findNearbyStores("강남역", context("강남역 근처 매장"));

        assertThat(recorder.isLocationRequired()).isFalse();
        assertThat(recorder.result()).isPresent();
    }

    @Test
    void returnsMessageInsteadOfThrowingWhenLocationLookupFails() {
        when(locationService.search(anyString())).thenThrow(new IllegalStateException("kakao down"));

        String result = storeTools.findNearbyStores("강남역", context("강남역 매장"));

        assertThat(result).isEqualTo(StoreTools.LOOKUP_FAILED_MESSAGE);
        assertThat(recorder.isLocationRequired()).isFalse();
        verify(storeService, never()).getNearbyStoreList(anyDouble(), anyDouble(), anyDouble(), anyList(), anyInt());
    }

    @Test
    void returnsMessageWhenStoreQueryFails() {
        when(locationService.search("강남역")).thenReturn(List.of(gangnam));
        when(storeService.getNearbyStoreList(37.4979, 127.0276, 3.0, List.of(), 5))
                .thenThrow(new IllegalStateException("db down"));

        String result = storeTools.findNearbyStores("강남역", context("강남역 매장"));

        assertThat(result).isEqualTo(StoreTools.LOOKUP_FAILED_MESSAGE);
        assertThat(recorder.result()).isEmpty();
    }

    @Test
    void worksWithoutRecorder() {
        String result = storeTools.findNearbyStores(null, new ToolContext(Map.of(StoreTools.QUESTION, "근처 매장")));

        assertThat(result).isEqualTo(StoreTools.NO_LOCATION_MESSAGE);
    }

    private ToolContext context(String question) {
        return new ToolContext(Map.of(StoreTools.QUESTION, question, StoreTools.RECORDER, recorder));
    }
}
