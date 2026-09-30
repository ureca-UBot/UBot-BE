package com.ubot.ai.tool;

import com.ubot.location.dto.LocationSearchResponse;
import com.ubot.location.service.LocationService;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.service.StoreService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** LLM이 필요할 때 호출하는 매장 조회 도구입니다. 좌표는 LLM이 아니라 서버가 ToolContext로 넣습니다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreTools {

    // AiService가 toolContext를 만들 때와 이 클래스가 읽을 때 같은 키를 씁니다.
    public static final String QUESTION = "question";
    public static final String LATITUDE = "latitude";
    public static final String LONGITUDE = "longitude";

    static final String NO_LOCATION_MESSAGE =
            "위치 정보가 없어 근처 매장을 조회할 수 없습니다. 사용자에게 기준이 될 장소나 지역을 알려 달라고 안내하세요.";
    static final String NO_STORE_MESSAGE = "반경 3km 안에 매장이 없습니다.";
    static final String LOOKUP_FAILED_MESSAGE = "매장 정보를 조회하지 못했습니다.";

    private static final double RADIUS_KM = 3.0;
    private static final int LIMIT = 5;

    private final StoreService storeService;
    private final LocationService locationService;

    @Tool(description = "근처 매장의 이름, 주소, 전화번호, 영업시간, 거리를 조회한다. "
            + "사용자가 역이나 동네 같은 장소를 직접 말한 경우에만 place에 그 장소명을 넣고, 말하지 않았으면 비워 둔다.")
    public String findNearbyStores(
            @ToolParam(description = "사용자가 직접 말한 기준 장소명. 예: 강남역", required = false) String place,
            ToolContext toolContext) {
        Map<String, Object> context = toolContext.getContext();
        try {
            double[] coordinate = resolveCoordinate(place, context);
            if (coordinate == null) {
                return NO_LOCATION_MESSAGE;
            }

            List<NearbyStoreResponseDto> stores =
                    storeService.getNearbyStoreList(coordinate[0], coordinate[1], RADIUS_KM, List.of(), LIMIT);
            if (stores.isEmpty()) {
                return NO_STORE_MESSAGE;
            }
            return stores.stream().map(this::formatStore).collect(Collectors.joining("\n"));
        } catch (RuntimeException exception) {
            // 도구 안의 카카오·DB 오류가 답변 전체 실패로 번지지 않게 안내 문구로 돌려줍니다.
            log.warn("매장 조회 도구 실패: place={}", place, exception);
            return LOOKUP_FAILED_MESSAGE;
        }
    }

    private double[] resolveCoordinate(String place, Map<String, Object> context) {
        // 질문에 실제로 있는 장소명만 사용합니다. LLM이 지어낸 장소로 조회하지 않게 합니다.
        if (isMentionedInQuestion(place, context.get(QUESTION))) {
            List<LocationSearchResponse> locations = locationService.search(place.strip());
            if (!locations.isEmpty()) {
                LocationSearchResponse location = locations.getFirst();
                return new double[] {location.latitude(), location.longitude()};
            }
        }
        if (context.get(LATITUDE) instanceof Number latitude && context.get(LONGITUDE) instanceof Number longitude) {
            return new double[] {latitude.doubleValue(), longitude.doubleValue()};
        }
        return null;
    }

    private boolean isMentionedInQuestion(String place, Object question) {
        if (!StringUtils.hasText(place) || !(question instanceof String text)) {
            return false;
        }
        return removeWhitespace(text).contains(removeWhitespace(place));
    }

    private String removeWhitespace(String value) {
        return value.replaceAll("\s+", "");
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
