package com.ubot.chat.context;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.dto.ContextSection;
import com.ubot.ai.dto.StoreMapResult;
import com.ubot.ai.tool.AiTool;
import com.ubot.ai.tool.NearbyStoreSearcher;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 내 위치로 요청했을 때는 LLM을 거치지 않고 바로 매장 조회 
 * 그게 아닌 경우 매장은 여기서 조회하지 않고, LLM이 필요할 때 호출할 매장 조회 도구만 붙입니다. 
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreIntentHandler implements IntentHandler {
	
	static final String SECTION_TITLE = "현재 위치 근처 매장";
	static final String LOOKUP_FAILED_CONTENT = "매장 정보를 조회하지 못했습니다. 매장 이름이나 주소를 추측하지 말고, "
			+ "잠시 후 다시 묻거나 지역·역 이름으로 물어봐 달라고 안내하세요. 질문의 다른 부분은 그대로 답하세요.";

	private final NearbyStoreSearcher nearbyStoreSearcher;
	
	@Override
	public Intent intent() {
		return Intent.STORE_DATA;
	}

	@Override
	public void contribute(ChatContext context, List<FaqSearchResponseDto> results, AnswerMaterials.Builder materials) {
		// 위치 없으면 도구 호출 경로
		if(context.location() == null) {
			materials.enableTool(AiTool.STORE_SEARCH);
			return;
		}
		
		// 내 위치로 재요청 한 경우 → 매장 바로 조회하는 경로
		// 내 위치 기준 재요청이면 기준이 확실하므로 LLM에게 다시 묻지 않고 바로 조회합니다.
		try {
			StoreMapResult result = nearbyStoreSearcher.search(context.location(), null);
			materials.addSection(new ContextSection(SECTION_TITLE, nearbyStoreSearcher.format(result)));
			// 빈 목록이어도 화면이 "주변에 매장 없음"을 지도와 함께 안내할 수 있게 담습니다.
			materials.storeMap(result);
		} catch (RuntimeException exception) {
			// 조회 오류가 답변 전체 실패로 번지지 않게 안내 섹션으로 대신합니다.
			log.warn("내 위치 기준 매장 조회 실패", exception);
			materials.addSection(new ContextSection(SECTION_TITLE, LOOKUP_FAILED_CONTENT));
		}
	}
}
