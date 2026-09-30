package com.ubot.chat.context;

import com.ubot.ai.dto.Location;

/** 핸들러가 공통으로 쓰는 요청 정보입니다. ChatService.generateAnswer에서 attempt로 만듭니다. location은 null일 수 있습니다. */
public record ChatContext(Long userId, String question, Location location) {
}
