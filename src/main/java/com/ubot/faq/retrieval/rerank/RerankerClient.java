package com.ubot.faq.retrieval.rerank;

import java.util.List;

/** 질문과 문서 쌍에 관련도 점수를 매기는 모델 서버 창구입니다. */
public interface RerankerClient {

    /**
     * 문서마다 점수를 하나씩, 입력 순서대로 돌려줍니다. 점수가 높을수록 질문과 관련이 깊습니다.
     *
     * @throws RerankException 서버 오류, 시간 초과, 또는 점수가 빠지거나 겹치거나 유한한 수가 아닌 응답
     */
    List<Double> score(String query, List<String> documents);
}
