package com.ubot.chat.job.dto;

import com.ubot.chat.dto.response.ChatStoreDto;

// 성공 작업의 화면용 결과. 답변 본문은 질문 로그에 있고 store는 매장 질문에만 있음
public record ChatJobResult(Long questionLogId, ChatStoreDto store) {
}
