package com.ubot.faq.retrieval;

import java.time.Duration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * FAQ 검색 설정입니다. 검색할 벡터 유형(FAQ_SEARCH_VECTOR_TYPE)은 FaqVectorService가 읽고 검사합니다.
 */
@ConfigurationProperties(prefix = "faq.retrieval")
public class FaqRetrievalProperties implements InitializingBean {

    private final Rerank rerank = new Rerank();

    public Rerank getRerank() {
        return rerank;
    }

    @Override
    public void afterPropertiesSet() {
        if (rerank.candidateK < 1) {
            throw new IllegalStateException("FAQ_RERANK_CANDIDATE_K는 1 이상이어야 합니다: " + rerank.candidateK);
        }
        requirePositive(rerank.connectTimeout, "FAQ_RERANK_CONNECT_TIMEOUT");
        requirePositive(rerank.readTimeout, "FAQ_RERANK_READ_TIMEOUT");
        if (rerank.maxConcurrent < 1) {
            throw new IllegalStateException("FAQ_RERANK_MAX_CONCURRENT는 1 이상이어야 합니다: " + rerank.maxConcurrent);
        }
        if (rerank.failureThreshold < 1) {
            throw new IllegalStateException(
                    "FAQ_RERANK_FAILURE_THRESHOLD는 1 이상이어야 합니다: " + rerank.failureThreshold);
        }
        requirePositive(rerank.failureCooldown, "FAQ_RERANK_FAILURE_COOLDOWN");
        // 꺼 두었을 때는 서버 주소와 모델 이름이 없어도 기동합니다.
        if (rerank.enabled && !StringUtils.hasText(rerank.baseUrl)) {
            throw new IllegalStateException("FAQ_RERANK_ENABLED=true이면 FAQ_RERANK_BASE_URL이 필요합니다.");
        }
        if (rerank.enabled && !StringUtils.hasText(rerank.model)) {
            throw new IllegalStateException("FAQ_RERANK_ENABLED=true이면 FAQ_RERANK_MODEL이 필요합니다.");
        }
    }

    private void requirePositive(Duration timeout, String name) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException(name + "은 0보다 커야 합니다: " + timeout);
        }
    }

    public static class Rerank {

        // 벡터 검색 후보를 Reranker로 다시 정렬할지 여부입니다. 꺼 두면 Reranker 서버가 없어도 됩니다.
        private boolean enabled = false;
        // 켰을 때 벡터 검색에서 가져와 Reranker에 넘길 후보 수입니다.
        private int candidateK = 10;
        private String model = "ubot-reranker";
        private String baseUrl = "http://localhost:8002";
        private Duration connectTimeout = Duration.ofMillis(500);
        private Duration readTimeout = Duration.ofMillis(1500);
        // Reranker를 쓰지 못했을 때 이미 받아 둔 벡터 검색 결과로 답할지 여부입니다.
        private boolean fallbackEnabled = true;
        // 동시에 나가는 Reranker 호출 수의 한도입니다. 넘치는 요청은 기다리지 않고 벡터 검색 결과로 답합니다.
        private int maxConcurrent = 32;
        // 연속으로 이만큼 실패하면 failureCooldown 동안 Reranker를 호출하지 않습니다.
        private int failureThreshold = 5;
        private Duration failureCooldown = Duration.ofSeconds(10);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCandidateK() {
            return candidateK;
        }

        public void setCandidateK(int candidateK) {
            this.candidateK = candidateK;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public boolean isFallbackEnabled() {
            return fallbackEnabled;
        }

        public void setFallbackEnabled(boolean fallbackEnabled) {
            this.fallbackEnabled = fallbackEnabled;
        }

        public int getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getFailureCooldown() {
            return failureCooldown;
        }

        public void setFailureCooldown(Duration failureCooldown) {
            this.failureCooldown = failureCooldown;
        }
    }
}
