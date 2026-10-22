package com.ubot.embedding.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgvector.PGvector;
import com.ubot.PgvectorTestConfiguration;
import com.ubot.embedding.service.EmbeddingProfileService;
import com.ubot.faq.enums.FaqVectorType;
import com.ubot.faq.repository.FaqVectorRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("임베딩 적체 지표 통합 테스트")
class EmbeddingBacklogMetricsTest {

	@Autowired private EmbeddingBacklogMetrics backlog;
	@Autowired private MeterRegistry registry;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private FaqVectorRepository vectors;
	@Autowired private EmbeddingProfileService profiles;

	private Long categoryId;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM outbox_events");
		jdbc.update("DELETE FROM faq_embeddings");
		jdbc.update("DELETE FROM old_faq");
		jdbc.update("DELETE FROM faq");
		jdbc.update("DELETE FROM faq_category");
		categoryId = jdbc.queryForObject(
				"INSERT INTO faq_category (name) VALUES ('적체 지표 테스트') RETURNING id", Long.class);
	}

	@Test
	@DisplayName("현재 Profile 벡터가 없는 variant와 FAQ를 고친 뒤 다시 임베딩되지 않은 variant를 센다")
	void countsMissingAndStaleFaqVectors() {
		Long profileId = profiles.getCurrentProfileId();
		Long otherProfileId = otherProfile();
		insertFaq(1L, 1, null);
		insertFaq(2L, 1, null);
		insertFaq(3L, 2, null);
		insertFaq(4L, 1, "2026-01-01 00:00:00");
		vectors.saveVectorForVariant(1L, profileId, FaqVectorType.QUESTION, 1, vector());
		// 다른 Profile의 벡터만 있으면 현재 검색에는 쓰이지 않으므로 없음으로 셈
		vectors.saveVectorForVariant(2L, otherProfileId, FaqVectorType.QUESTION, 1, vector());
		vectors.saveVectorForVariant(3L, profileId, FaqVectorType.QUESTION, 1, vector());

		backlog.refresh();

		assertThat(gauge("faq.embeddings.missing")).as("2번만 현재 Profile 벡터가 없음(삭제된 4번 제외)").isEqualTo(1);
		assertThat(gauge("faq.embeddings.stale")).as("3번은 FAQ 버전 2인데 벡터는 버전 1").isEqualTo(1);
	}

	@Test
	@DisplayName("outbox에 남은 이벤트를 대기·재시도 중·재시도 소진으로 나눠 센다")
	void countsOutboxBacklogByRetryState() {
		// 테스트 문맥의 OutboxWorker가 가져가지 않도록 다음 시도 시각을 미래로 둠
		insertOutbox("a", 0);
		insertOutbox("b", 2);
		insertOutbox("c", 5);

		backlog.refresh();

		assertThat(gauge("outbox.events.pending")).isEqualTo(2);
		assertThat(gauge("outbox.events.retrying")).isEqualTo(1);
		assertThat(gauge("outbox.events.dead")).isEqualTo(1);
	}

	private double gauge(String name) {
		return registry.get(name).gauge().value();
	}

	private void insertFaq(Long id, int version, String deletedAt) {
		jdbc.update("""
				INSERT INTO faq (id, category_id, question, answer, version, deleted_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?::timestamp, now(), now())
				""", id, categoryId, "질문 " + id, "답변 " + id, version, deletedAt);
		jdbc.update("INSERT INTO faq_question_variants (id, origin_faq_id, question) VALUES (?, ?, ?)",
				id, id, "질문 " + id);
	}

	private Long otherProfile() {
		jdbc.update("""
				INSERT INTO embedding_profiles (provider, model_name, dimensions, profile_version)
				VALUES ('openai-compatible', 'backlog-test-model', 1024, 1)
				ON CONFLICT (provider, model_name, dimensions, profile_version) DO NOTHING
				""");
		return jdbc.queryForObject(
				"SELECT profile_id FROM embedding_profiles WHERE model_name = 'backlog-test-model'", Long.class);
	}

	private void insertOutbox(String key, int retryCount) {
		jdbc.update("""
				INSERT INTO outbox_events (event_type, dedup_key, payload, retry_count, next_attempt_at)
				VALUES ('UNANSWERED_QUESTION', ?, '{}'::jsonb, ?, LOCALTIMESTAMP + INTERVAL '1 day')
				""", "backlog-test-" + key, retryCount);
	}

	private static PGvector vector() {
		float[] values = new float[1024];
		values[0] = 1.0f;
		return new PGvector(values);
	}
}
