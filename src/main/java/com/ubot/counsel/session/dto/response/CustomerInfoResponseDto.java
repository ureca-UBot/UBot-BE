package com.ubot.counsel.session.dto.response;
import java.util.List;
public record CustomerInfoResponseDto(String name, List<Line> lines, List<HomeProduct> homeProducts) {
    public record Line(Long subscriptionId, String phoneNumber, String status, String planName,
        List<String> addons, List<String> bundles, List<String> roamings) {}
    public record HomeProduct(Long subscriptionId, String productType, String name) {}
}
