package com.ubot.faq.retrieval.rerank;

/** Reranker 서버를 보호하려고 호출하지 않고 건너뛰었을 때 던집니다. 서버를 호출해서 실패한 경우와 구분합니다. */
public class RerankSkippedException extends RerankException {

    public RerankSkippedException(String message) {
        super(message);
    }
}
