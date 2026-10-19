package com.ubot.ai.dto;

/**
 * LLM 답변과, 화면에 따로 보여 줄 조회 결과입니다. storeMap은 매장을 조회하지 않았으면 null입니다.
 * userData는 답변에 쓰인 요금제·사용량·청구서 카드이며, 없으면 null입니다.
 */
public record AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired, UserDataCards userData) {

    public AiAnswer(String answer, StoreMapResult storeMap, boolean locationRequired) {
        this(answer, storeMap, locationRequired, null);
    }
}
