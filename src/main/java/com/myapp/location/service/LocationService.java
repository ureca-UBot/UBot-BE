package com.myapp.location.service;

import com.myapp.location.client.KakaoLocalClient;
import com.myapp.location.dto.LocationSearchResponse;
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
