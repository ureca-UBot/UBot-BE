package com.ubot.embedding.metrics;

import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.global.config.AppRole;
import com.ubot.outbox.OutboxWorker;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 벡터를 다시 만들어야 하는 FAQ variant 수와 미응답 질문 비동기 처리(outbox) 적체를 주기적으로 세어 지표로 내보냅니다.
 * 스크레이프마다 DB를 조회하지 않도록 마지막으로 센 값만 Gauge로 읽고, 처음 세기 전에는 NaN입니다.
 */
@Slf4j
@Component
@ConditionalOnExpression(AppRole.RUNS_SCHEDULERS)
public class EmbeddingBacklogMetrics {

	// 백필(hasCurrentVector)과 같은 기준: 현재 Profile의 QUESTION 벡터가 없거나, 있어도 FAQ를 고친 뒤의 버전이 아님
	private static final String FAQ_BACKLOG_SQL = """
			SELECT count(*) FILTER (WHERE e.faq_question_variant_id IS NULL) AS missing,
			       count(*) FILTER (WHERE e.faq_version <> f.version) AS stale
			FROM faq_question_variants v
			JOIN faq f ON f.id = v.origin_faq_id AND f.deleted_at IS NULL
			LEFT JOIN faq_embeddings e ON e.faq_question_variant_id = v.id
			     AND e.profile_id = ? AND e.vector_type = 'QUESTION'
			""";
	// 처리된 이벤트는 지워지므로 남은 행이 적체임. 재시도 횟수를 다 쓴 행은 다시 처리되지 않음
	private static final String OUTBOX_BACKLOG_SQL = """
			SELECT count(*) FILTER (WHERE retry_count < ?) AS pending,
			       count(*) FILTER (WHERE retry_count > 0 AND retry_count < ?) AS retrying,
			       count(*) FILTER (WHERE retry_count >= ?) AS dead
			FROM outbox_events
			""";

	private final JdbcTemplate jdbc;
	private final EmbeddingProfileService profiles;
	private volatile double faqMissing = Double.NaN;
	private volatile double faqStale = Double.NaN;
	private volatile double outboxPending = Double.NaN;
	private volatile double outboxRetrying = Double.NaN;
	private volatile double outboxDead = Double.NaN;

	public EmbeddingBacklogMetrics(JdbcTemplate jdbc, EmbeddingProfileService profiles, MeterRegistry metrics) {
		this.jdbc = jdbc;
		this.profiles = profiles;
		metrics.gauge("faq.embeddings.missing", this, backlog -> backlog.faqMissing);
		metrics.gauge("faq.embeddings.stale", this, backlog -> backlog.faqStale);
		metrics.gauge("outbox.events.pending", this, backlog -> backlog.outboxPending);
		metrics.gauge("outbox.events.retrying", this, backlog -> backlog.outboxRetrying);
		metrics.gauge("outbox.events.dead", this, backlog -> backlog.outboxDead);
	}

	// 한쪽 조회가 실패해도 다른 쪽은 갱신함. 실패한 값은 이전 값을 유지함
	@Scheduled(initialDelayString = "30s", fixedDelayString = "${app.metrics.backlog-refresh:5m}")
	public void refresh() {
		try {
			jdbc.query(FAQ_BACKLOG_SQL, row -> {
				faqMissing = row.getLong("missing");
				faqStale = row.getLong("stale");
			}, profiles.getCurrentProfileId());
		} catch (RuntimeException exception) {
			log.warn("FAQ 벡터 적체를 세지 못했습니다. 다음 주기에 다시 셉니다.", exception);
		}
		try {
			jdbc.query(OUTBOX_BACKLOG_SQL, row -> {
				outboxPending = row.getLong("pending");
				outboxRetrying = row.getLong("retrying");
				outboxDead = row.getLong("dead");
			}, OutboxWorker.MAX_RETRIES, OutboxWorker.MAX_RETRIES, OutboxWorker.MAX_RETRIES);
		} catch (RuntimeException exception) {
			log.warn("미응답 질문 비동기 처리 적체를 세지 못했습니다. 다음 주기에 다시 셉니다.", exception);
		}
	}
}
