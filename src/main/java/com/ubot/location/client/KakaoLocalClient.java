package com.ubot.location.client;

import java.util.List;

import com.ubot.location.dto.LocationSearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoLocalClient {
    private static final int DEFAULT_RESULT_SIZE = 5;

    private final RestClient restClient;

    public KakaoLocalClient(@Value("${kakao.local.api-key}") String apiKey) {
        this.restClient = RestClient.builder()
                .baseUrl("https://dapi.kakao.com")
                .defaultHeader("Authorization", "KakaoAK " + apiKey)
                .build();
    }

    public List<LocationSearchResponse> searchAddress(String query) {
        KakaoAddressResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/address.json")
                        .queryParam("query", query)
                        .queryParam("size", DEFAULT_RESULT_SIZE)
                        .build())
                .retrieve()
                .body(KakaoAddressResponse.class);

        if (response == null || response.documents() == null) {
            return List.of();
        }

        return response.documents().stream()
                .map(document -> {
                    String roadAddress = document.road_address() == null
                            ? null
                            : document.road_address().address_name();
                    String name = roadAddress == null || roadAddress.isBlank()
                            ? document.address_name()
                            : roadAddress;

                    return new LocationSearchResponse(
                            name,
                            document.address_name(),
                            roadAddress,
                            Double.parseDouble(document.y()),
                            Double.parseDouble(document.x())
                    );
                })
                .toList();
    }

    public List<LocationSearchResponse> searchKeyword(String query) {
        KakaoKeywordResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/local/search/keyword.json")
                        .queryParam("query", query)
                        .queryParam("size", DEFAULT_RESULT_SIZE)
                        .build())
                .retrieve()
                .body(KakaoKeywordResponse.class);

        if (response == null || response.documents() == null) {
            return List.of();
        }

        return response.documents().stream()
                .map(document -> new LocationSearchResponse(
                        document.place_name(),
                        document.address_name(),
                        document.road_address_name(),
                        Double.parseDouble(document.y()),
                        Double.parseDouble(document.x())
                ))
                .toList();
    }

    private record KakaoAddressResponse(List<KakaoAddressDocument> documents) {}

    private record KakaoAddressDocument(
            String address_name,
            String x,
            String y,
            KakaoRoadAddress road_address
    ) {}

    private record KakaoRoadAddress(String address_name) {}

    private record KakaoKeywordResponse(List<KakaoKeywordDocument> documents) {}

    private record KakaoKeywordDocument(
            String place_name,
            String address_name,
            String road_address_name,
            String x,
            String y
    ) {}
}
