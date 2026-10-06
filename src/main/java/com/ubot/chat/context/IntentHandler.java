package com.ubot.chat.context;

import com.ubot.ai.dto.AnswerMaterials;
import com.ubot.faq.dto.response.FaqSearchResponseDto;
import com.ubot.faq.enums.Intent;
import java.util.List;

/**
 * intent 하나의 답변 자료를 채웁니다.
 * 약속: 비로그인·사용자 없음처럼 예상 가능한 상황은 예외 대신 안내 섹션으로 채웁니다.
 * ChatAnswerExecutor는 GlobalException이 나면 답변 전체를 실패 처리하기 때문입니다.
 */
public interface IntentHandler {
	Intent intent();

	void contribute(ChatContext context, List<FaqSearchResponseDto> results, AnswerMaterials.Builder materials);
}
