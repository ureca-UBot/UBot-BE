package com.ubot.chat.dto.response;

import com.ubot.ai.dto.StoreMapResult;

/**
 * 매장 질문의 화면용 결과입니다. 매장과 무관한 답변에서는 응답에 담지 않습니다(null).
 * locationRequired가 true면 기준 위치를 정하지 못한 것이므로, 화면이 위치 권한을 확인한 뒤 좌표와 함께 다시 요청합니다.
 */
public record ChatStoreDto(boolean locationRequired, StoreMapResult map) {

	/** 위치 요청도 지도 결과도 없으면 매장과 무관한 답변이므로 null을 돌려줍니다. */
	public static ChatStoreDto of(boolean locationRequired, StoreMapResult map) {
		return locationRequired || map != null ? new ChatStoreDto(locationRequired, map) : null;
	}
}