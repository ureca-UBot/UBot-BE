package com.ubot.chat.context;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.List;
import org.springframework.stereotype.Component;

/** 이미 검색한 GENERAL FAQ를 그대로 참고 자료로 씁니다. 추가 조회는 없습니다. */
@Component
public class GeneralIntentHandler implements IntentHandler {
	@Override
	public Intent intent() {
		return Intent.GENERAL;
	}

	@Override
	public void contribute(ChatContext context, List<FaqSearchResponseDto> results, AnswerMaterials.Builder materials) {
		materials.addFaqs(results);
	}
}
