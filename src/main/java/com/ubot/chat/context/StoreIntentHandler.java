package com.ubot.chat.context;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.ai.tool.AiTool;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.List;
import org.springframework.stereotype.Component;

/** 매장은 여기서 조회하지 않고, LLM이 필요할 때 호출할 매장 조회 도구만 붙입니다. */
@Component
public class StoreIntentHandler implements IntentHandler {
	@Override
	public Intent intent() {
		return Intent.STORE_DATA;
	}

	@Override
	public void contribute(ChatContext context, List<FaqSearchResponseDto> results, AnswerMaterials.Builder materials) {
		materials.enableTool(AiTool.STORE_SEARCH);
	}
}
