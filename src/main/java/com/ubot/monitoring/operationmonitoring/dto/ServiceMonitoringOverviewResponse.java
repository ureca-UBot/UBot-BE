package com.ubot.monitoring.operationmonitoring.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 운영 개요. 비율·백분위·횟수 중 이름에 기간이 없는 값은 조회 기간(window.range) 기준이고,
 * 데이터가 없거나 분모가 0이면 null입니다.
 */
public record ServiceMonitoringOverviewResponse(
        Window window,
        ServiceStatuses services,
        Summary summary,
        Series series,
        Worker worker,
        Llm llm,
        Retrieval retrieval,
        EmbeddingLifecycle embedding,
        DatabaseLatency database,
        RedisStatus redis
) {
    public ServiceMonitoringOverviewResponse(Window window, ServiceStatuses services, Summary summary, Series series) {
        this(window, services, summary, series, null, null, null, null, null, null);
    }

    /**
     * failuresPerSecond·meanExecutionSeconds는 최근 5분 기준입니다.
     * terminalFailureRatio는 답을 못 찾은 결과(CHAT-012·013)를 뺀 실패 비율이고, 대기 만료·전달 횟수 소진도 포함합니다.
     * failureRatioByCode는 전체 완료 중 실패 코드별 비율, reclaimsByReason은 heartbeat·idle별 넘겨받은 횟수입니다.
     */
    public record Worker(Double queued, Double oldestQueuedSeconds, Double active,
                         Double failuresPerSecond, Double meanExecutionSeconds,
                         Double p95ExecutionSeconds, Double p95QueueWaitSeconds,
                         Double retryRatio, Double terminalFailureRatio, Map<String, Double> failureRatioByCode,
                         Map<String, Double> reclaimsByReason, Double leaseLosses, Double heartbeatFailures) {
    }

    /** 동시 호출 값은 api 역할을 뺀 현재 값입니다. outputGuardRejectionsByRule은 거절 규칙별 횟수입니다. */
    public record Llm(Double p95AnswerSeconds, Double p95JudgmentSeconds, Double timeoutRatio, Double errorRatio,
                      Double concurrencyInUse, Double concurrencyLimit, Double concurrencyWaiting,
                      Double p95PermitWaitSeconds, Double outputGuardRejectionRatio,
                      Map<String, Double> outputGuardRejectionsByRule) {
    }

    /** topSimilarityBuckets는 1위 후보 유사도의 누적 건수(Prometheus le 기준, 키는 구간 상한)입니다. */
    public record Retrieval(Double embeddingP95Seconds, Double embeddingErrorRatio, Double vectorSearchP95Seconds,
                            Double noFaqRatio, Double insufficientFaqRatio,
                            Double topSimilarityP10, Double topSimilarityP50, Map<String, Double> topSimilarityBuckets) {
    }

    /** 적체 수는 api 역할이 주기적으로 센 마지막 값입니다. counselFailures는 상담 임베딩 실패 횟수입니다. */
    public record EmbeddingLifecycle(Double faqMissing, Double faqStale,
                                     Double outboxPending, Double outboxRetrying, Double outboxDead,
                                     Double counselFailures) {
    }

    /** p95UsageMillis는 커넥션을 빌려 쓴 시간(느린 쿼리·긴 트랜잭션의 대리 지표)입니다. */
    public record DatabaseLatency(Double meanAcquireMillis, Double maxAcquireMillis, Double p95UsageMillis,
                                  Double longestTransactionSeconds) {
    }

    /** 스트림 값은 답변 작업 스트림(chat:jobs) 기준입니다. lag는 Redis 7 이상에서만 나옵니다. */
    public record RedisStatus(Double usedMemoryBytes, Double maxMemoryBytes,
                              Double streamLength, Double pendingMessages, Double consumerLag) {
    }
    public record Window(String range, Instant from, Instant to, long sampleIntervalSeconds) {
    }

    public record ServiceStatuses(ServiceStatus backend, ServiceStatus postgres, ServiceStatus redis, ServiceStatus worker) {
        public ServiceStatuses(ServiceStatus backend, ServiceStatus postgres, ServiceStatus redis) {
            this(backend, postgres, redis, ServiceStatus.UNKNOWN);
        }
    }

    public enum ServiceStatus {
        UP, DOWN, UNKNOWN
    }

    public record Summary(
            Double apiRps,
            Double api5xxRate,
            Double apiP95Millis,
            Double jvmHeapUsageRate,
            DatabasePool databasePool
    ) {
    }

    public record DatabasePool(Double active, Double idle, Double pending, Double max) {
    }

    public record Series(
            List<MetricPoint> apiRps,
            ErrorRate errorRate,
            ApiLatency apiLatencyMillis,
            Backend backend,
            DatabasePoolSeries databasePool,
            ContainerResources containers
    ) {
    }

    public record ErrorRate(List<MetricPoint> client4xx, List<MetricPoint> server5xx) {
    }

    public record ApiLatency(List<MetricPoint> p50, List<MetricPoint> p95, List<MetricPoint> p99) {
    }

    public record Backend(
            List<MetricPoint> cpuUsageRate,
            List<MetricPoint> jvmHeapUsageRate,
            List<MetricPoint> gcPauseSecondsPerSecond,
            List<MetricPoint> liveThreads
    ) {
    }

    public record DatabasePoolSeries(
            List<MetricPoint> active,
            List<MetricPoint> idle,
            List<MetricPoint> pending
    ) {
    }

    public record ContainerResources(
            Map<String, List<MetricPoint>> cpuUsageRate,
            Map<String, List<MetricPoint>> memoryBytes
    ) {
    }
}
