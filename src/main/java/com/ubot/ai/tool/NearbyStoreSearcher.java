package com.ubot.ai.tool;

import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.service.StoreService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 기준 좌표 근처 매장을 조회하고 LLM이 읽을 문자열로 정리합니다.
 * 매장 조회 도구(StoreTools)와 내 위치 기준 재요청(StoreIntentHandler)이 같은 반경·개수·형식을 쓰게 합니다.
 */
@Component
public class NearbyStoreSearcher {

//    static final double RADIUS_KM = 3.0;
//    static final int LIMIT = 5;
//
//    static final String NO_STORE_MESSAGE = "반경 3km 안에 매장이 없습니다.";
//    // 매장 목록은 화면 지도에 따로 표시되므로 답변 문장에는 요약만 쓰게 합니다.
    static final String MAP_NOTICE =
            "이 매장 목록은 사용자 화면의 지도에 함께 표시됩니다. 답변에는 매장 수와 가장 가까운 매장의 이름·거리만 간단히 적으세요.";

    private final StoreService storeService;
    private final double radiusKm;
    private final int limit;
    
    public NearbyStoreSearcher(
            StoreService storeService,
            @Value("${CHAT_STORE_RADIUS_KM:3.0}") double radiusKm,
            @Value("${CHAT_STORE_LIMIT:5}") int limit) {
        // 잘못된 운영값은 요청 중 "조회 실패"로 숨지 않게 기동 시점에 막습니다. 개수 상한은 매장 찾기 API와 같습니다.
        if (radiusKm <= 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("매장 검색 반경은 0보다 크고, 개수는 1~100이어야 합니다.");
        }
        this.storeService = storeService;
        this.radiusKm = radiusKm;
        this.limit = limit;
    }

    /** 기준 좌표 반경 3km 안의 매장을 가까운 순으로 최대 5개 조회합니다. placeName이 null이면 사용자 현재 위치 기준입니다. */
    public StoreMapResult search(Location center, String placeName) {
        List<NearbyStoreResponseDto> stores = storeService.getNearbyStoreList(
                center.latitude(), center.longitude(), radiusKm, List.of(), limit);
        return new StoreMapResult(center, placeName, radiusKm, stores);
    }

    /** 조회 결과를 LLM이 읽을 문자열로 만듭니다. 매장이 없으면 그 사실만 알립니다. */
    public String format(StoreMapResult result) {
        if (result.stores().isEmpty()) {
            return noStoreMessage(result.radiusKm());
        }
        return result.stores().stream().map(this::formatStore).collect(Collectors.joining("\n"))
                + "\n\n" + MAP_NOTICE;
    }

    static String noStoreMessage(double radiusKm) {
        // 3.0 → "3", 1.5 → "1.5"처럼 불필요한 소수점을 빼고 표시합니다.
        return "반경 " + BigDecimal.valueOf(radiusKm).stripTrailingZeros().toPlainString() + "km 안에 매장이 없습니다.";
    }
    
    private String formatStore(NearbyStoreResponseDto store) {
        return "[매장 ID: " + store.storeId() + "] " + store.storeName()
                + " | " + store.address()
                + " | " + valueOrUnknown(store.phoneNumber())
                + " | 영업시간 " + valueOrUnknown(store.businessHours())
                + " | " + String.format(Locale.ROOT, "%.1fkm", store.distanceKm());
    }

    private String valueOrUnknown(String value) {
        return StringUtils.hasText(value) ? value : "정보 없음";
    }
}
