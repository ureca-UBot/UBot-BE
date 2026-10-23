package com.ubot.chat.dto;

import java.util.List;

/** FAQ 검색에서 이미 생성한 하위 질문 벡터를 통신지도 저장 작업에 전달한다. */
public record SurgingQuestionEmbedding(long profileId, List<Float> vector) {

    public SurgingQuestionEmbedding {
        vector = List.copyOf(vector);
    }
}
