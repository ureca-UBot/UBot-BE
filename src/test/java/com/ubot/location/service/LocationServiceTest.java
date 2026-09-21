package com.ubot.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ubot.location.client.KakaoLocalClient;
import com.ubot.location.dto.LocationSearchResponse;

@DisplayName("위치 검색 Service 테스트")
class LocationServiceTest {

    private final KakaoLocalClient kakaoLocalClient = mock(KakaoLocalClient.class);
    private final LocationService locationService = new LocationService(kakaoLocalClient);

    @Test
    @DisplayName("주소 검색 결과가 있으면 키워드 검색을 호출하지 않는다")
    void returnsAddressResultsWithoutKeywordSearch() {
        LocationSearchResponse addressResult = location("서울 강남구 강남대로 396");
        when(kakaoLocalClient.searchAddress("강남대로 396"))
                .thenReturn(List.of(addressResult));

        List<LocationSearchResponse> result = locationService.search("강남대로 396");

        assertThat(result).containsExactly(addressResult);
        verify(kakaoLocalClient, never()).searchKeyword("강남대로 396");
    }

    @Test
    @DisplayName("주소 검색 결과가 없으면 키워드 검색 결과를 반환한다")
    void fallsBackToKeywordSearchWhenAddressResultsAreEmpty() {
        LocationSearchResponse keywordResult = location("강남역 2호선");
        when(kakaoLocalClient.searchAddress("강남역")).thenReturn(List.of());
        when(kakaoLocalClient.searchKeyword("강남역")).thenReturn(List.of(keywordResult));

        List<LocationSearchResponse> result = locationService.search("강남역");

        assertThat(result).containsExactly(keywordResult);
        verify(kakaoLocalClient).searchKeyword("강남역");
    }

    private LocationSearchResponse location(String name) {
        return new LocationSearchResponse(
                name,
                "서울 강남구 역삼동 858",
                "서울 강남구 강남대로 396",
                37.498,
                127.028
        );
    }
}
