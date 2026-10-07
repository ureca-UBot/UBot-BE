package com.ubot.chat.context;

import com.ubot.ai.dto.Location;

/** 핸들러가 공통으로 쓰는 요청 정보입니다. ChatAnswerProcessor.generateAnswer에서 attempt로 만듭니다.
 * location은 사용자가 "내 위치 기준"으로 다시 요청했을 때만 있습니다. 그 외에는 좌표를 받아도 null입니다.
 */
public record ChatContext(Long userId, String question, Location location) {
}
