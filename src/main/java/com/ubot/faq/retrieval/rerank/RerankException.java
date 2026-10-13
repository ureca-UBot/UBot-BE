package com.ubot.faq.retrieval.rerank;

/** Reranker 서버를 호출하지 못했거나 응답을 쓸 수 없을 때 던집니다. FaqRetrievalService가 이 예외를 받으면 벡터 검색 결과로 답합니다. */
public class RerankException extends RuntimeException {

    public RerankException(String message) {
        super(message);
    }

    public RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}
