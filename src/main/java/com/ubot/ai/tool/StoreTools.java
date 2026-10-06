package com.ubot.ai.tool;

import com.ubot.ai.dto.Location;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.location.dto.LocationSearchResponse;
import com.ubot.location.service.LocationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public static final String RECORDER = "storeSearchRecorder";

    static final String NO_LOCATION_MESSAGE =
            "사용자가 기준 장소를 말하지 않아 근처 매장을 조회할 수 없습니다. 매장 이름이나 주소를 추측하지 말고, "
                    + "위치 권한을 허용하거나 지역·역 이름을 알려 달라고 안내하세요. 질문의 다른 부분은 그대로 답하세요.";
    static final String LOOKUP_FAILED_MESSAGE = "매장 정보를 조회하지 못했습니다.";
    
    // 질문에 없는 지역을 지어냈을 때 재요청 프롬프트
    static final String PLACE_NOT_IN_QUESTION_MESSAGE =
            "'%s'은(는) 사용자 질문에 없는 장소입니다. 질문에 쓰인 철자 그대로(오타가 있어도 고치지 말고) place에 넣어 다시 호출하고, "
                    + "사용자가 장소를 말하지 않았다면 place를 비워 다시 호출하세요. "
                    + "다시 호출하지 않는다면 매장 이름이나 주소를 추측하지 말고, 기준이 될 지명이나 역 이름을 알려 달라고 안내하세요.";
    // 카카오 검색 결과가 없을 때 재요청 프롬프트
    static final String PLACE_NOT_FOUND_MESSAGE =
            "'%s' 위치를 찾지 못했습니다. 매장 이름이나 주소를 추측하지 말고, "
                    + "정확한 지명이나 역 이름을 다시 알려 달라고 안내하세요. 질문의 다른 부분은 그대로 답하세요.";
    
    // 장소가 아니라 "기준 위치가 없다"는 뜻의 일반 단어입니다.
    private static final Set<String> GENERIC_WORDS = Set.of(
            "근처", "주변", "인근", "가까운", "가까이", "제일", "가장",
            "여기", "이근처", "지금", "현재", "현위치", "위치",
            "내", "나", "제", "우리", "집", "동네",
            "매장", "대리점", "지점", "직영점", "판매점", "가게", "곳", "쪽");
    private static final List<String> GENERIC_SUFFIXES = List.of("근처", "주변", "인근");

    private final NearbyStoreSearcher nearbyStoreSearcher;
    private final LocationService locationService;

    @Tool(description = "근처 매장의 이름, 주소, 전화번호, 영업시간, 거리를 조회한다. "
            + "사용자가 역이나 동네 같은 장소를 직접 말한 경우에만 place에 그 장소명을 질문에 쓰인 철자 그대로 넣는다. "
            + "오타가 있어도 고치지 않는다. "
            + "'내 근처', '가까운 곳'처럼 장소 없이 물었다면 place를 비워 둔다. "
            + "사용자가 말하지 않은 장소를 추측해서 넣지 않는다.")
    public String findNearbyStores(
            @ToolParam(description = "사용자가 직접 말한 기준 장소명 그대로. 예: 강남역. 장소를 말하지 않았으면 비운다.", required = false) String place,
            ToolContext toolContext) {
    	
        Map<String, Object> context = toolContext.getContext();
        StoreSearchRecorder recorder = context.get(RECORDER) instanceof StoreSearchRecorder value ? value : null;
        
        // AiService가 항상 넣는 값이지만 만약 없으면 빈 문자열로 둔다. -> 어떤 장소도 질문에 있는 것으로 보지 않게 합니다.
        String question = context.get(QUESTION) instanceof String value ? value : "";
        
        // 장소명이 비었거나 일반 단어뿐이면 사용자가 특정 장소를 말하지 않은 것이 확실하므로 사용자의 위치를 요청합니다.
        String placeName = extractPlaceName(place); // LLM이 넣어준 place 문자열에서 장소명만 남는다.
        if (placeName == null) {
            if (recorder != null) {
            	// 프론트로 locationRequired:true 전달
                recorder.markLocationRequired();
            }
            return NO_LOCATION_MESSAGE;
        }
        
        // 장소 명이 있는데 질문에는 없을 때. 즉 장소 명을 지어냈을 때. 오타 입력에도 발생할 수 있음
        // ex1) 질문:서울역, placeName:"역삼역"
        // ex2) 질문:역심역, placeName:"역삼역" -> 프롬프트로 제어하지만, LLM이 오타를 알아서 교정할 경우,
        // 재요청 프롬프트를 반환한다.
        if (!isMentionedInQuestion(placeName, question)) {
            return String.format(PLACE_NOT_IN_QUESTION_MESSAGE, placeName);
        }
        
        
        try {
            List<LocationSearchResponse> locations = locationService.search(placeName);
            
            // 사용자가 장소를 말했으므로 못 찾아도 현재 위치를 요청하지 않습니다. 다시 알려 달라고만 안내합니다.
            if (locations.isEmpty()) {
                return String.format(PLACE_NOT_FOUND_MESSAGE, placeName);
            }
            
            LocationSearchResponse found = locations.getFirst();
            Location center = new Location(found.latitude(), found.longitude());
            StoreMapResult result = nearbyStoreSearcher.search(center, placeName);
            // 빈 결과도 기록해 화면이 "주변에 매장 없음"을 지도와 함께 안내할 수 있게 합니다.
            if (recorder != null) {
                recorder.record(result);
            }
            
            return nearbyStoreSearcher.format(result);
        } catch (RuntimeException exception) {
            // 도구 안의 카카오·DB 오류가 답변 전체 실패로 번지지 않게 안내 문구로 돌려줍니다.
            log.warn("매장 조회 도구 실패: place={}, placeName={}", place, placeName, exception);
            return LOOKUP_FAILED_MESSAGE;
        }
    }

    /** 사용자의 오타를 LLM이 추측해서 답변한 경우 통과하지 못함. 철자가 완전히 일치해야 통과. */
    private boolean isMentionedInQuestion(String placeName, String question) {
        return removeWhitespace(question).contains(removeWhitespace(placeName));
    }

    private String removeWhitespace(String value) {
        return value.replaceAll("\\s+", "");
    }

    /** 일반 단어를 걷어 낸 장소명을 돌려줍니다. 남는 게 없으면 null입니다. */
    private String extractPlaceName(String place) {
        if (!StringUtils.hasText(place)) return null;
        List<String> tokens = new ArrayList<>();
        for (String token : place.strip().split("\\s+")) {
            // "강남역근처"처럼 붙여 쓴 경우 끝의 일반 단어만 떼어 냅니다.
            for (String suffix : GENERIC_SUFFIXES) {
                if (token.length() > suffix.length() && token.endsWith(suffix)) {
                    token = token.substring(0, token.length() - suffix.length());
                }
            }
            if (!GENERIC_WORDS.contains(token)) tokens.add(token);
        }
        String name = String.join(" ", tokens);
        return name.length() >= 2 ? name : null;
    }
}
