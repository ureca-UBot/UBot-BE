package com.ubot.chat.job.dto;

import com.ubot.ai.dto.UserDataCards;
import com.ubot.chat.dto.response.ChatStoreDto;

// 성공 작업의 화면용 결과. 답변 본문은 질문 로그에 있고 store는 매장 질문에만, userData는 가입 정보 답변에만 있음
// result_payload(JSON)로 저장되며, userData가 없던 이전 작업은 읽을 때 null이 됨
public record ChatJobResult(Long questionLogId, ChatStoreDto store, UserDataCards userData) {

	public ChatJobResult(Long questionLogId, ChatStoreDto store) {
		this(questionLogId, store, null);
	}
}
