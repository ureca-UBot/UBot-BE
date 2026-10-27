package com.ubot.location.service;

import com.ubot.location.client.KakaoLocalClient;
import com.ubot.location.dto.LocationSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LocationService {
    private final KakaoLocalClient kakaoLocalClient;

    public List<LocationSearchResponse> search(String query) {
        List<LocationSearchResponse> addressResults = kakaoLocalClient.searchAddress(query);
        if (!addressResults.isEmpty()) {
            return addressResults;
        }

        return kakaoLocalClient.searchKeyword(query);
    }
}
