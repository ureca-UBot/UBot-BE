package com.ubot.monitoring.faqmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.faq.enums.Intent;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import com.ubot.monitoring.faqmonitoring.dto.request.FaqImprovementExclusionRequest;
import com.ubot.monitoring.faqmonitoring.service.FaqMonitoringService;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
class FaqImprovementExclusionConcurrencyIntegrationTest {

	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private FaqMonitoringService monitoringService;

	private long userId;
	private long categoryId;
	private long faqId;

	@AfterEach
	void cleanUp() {
		if (faqId != 0) {
			jdbcTemplate.update("DELETE FROM faq WHERE id = ?", faqId);
		}
		if (categoryId != 0) {
			jdbcTemplate.update("DELETE FROM faq_category WHERE id = ?", categoryId);
		}
		if (userId != 0) {
			jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
		}
	}

	@Test
	@DisplayName("동일한 개선 제외를 동시에 등록해도 예외 없이 하나만 생성한다")
	void createsOnlyOneExclusionForConcurrentDuplicateRequests() throws Exception {
		setUpFaq();
		FaqImprovementExclusionRequest request =
				new FaqImprovementExclusionRequest(FaqImprovementType.LOW_USAGE, null);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> runExclusionRequest(request, ready, start));
			var second = executor.submit(() -> runExclusionRequest(request, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
		}

		Integer exclusionCount = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM faq_improvement_exclusion
				WHERE faq_id = ?
					AND improvement_type = 'LOW_USAGE'
					AND faq_version = 1
					AND released_at IS NULL
				""", Integer.class, faqId);
		assertThat(exclusionCount).isEqualTo(1);
	}

	private void runExclusionRequest(
			FaqImprovementExclusionRequest request,
			CountDownLatch ready,
			CountDownLatch start
	) {
		try {
			excludeWhenStarted(request, ready, start);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Concurrent exclusion request was interrupted", exception);
		}
	}

	private void excludeWhenStarted(
			FaqImprovementExclusionRequest request,
			CountDownLatch ready,
			CountDownLatch start
	) throws InterruptedException {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Concurrent exclusion requests did not start in time");
		}
		monitoringService.excludeFaqImprovement(faqId, request, userId);
	}

	private void setUpFaq() {
		userId = jdbcTemplate.queryForObject("""
				INSERT INTO users (email, password_hash, name)
				VALUES (?, 'hash', 'concurrency-test-user')
				RETURNING user_id
				""", Long.class, UUID.randomUUID() + "@test.com");
		categoryId = jdbcTemplate.queryForObject("""
				INSERT INTO faq_category (name)
				VALUES (?)
				RETURNING id
				""", Long.class, "concurrency-test-category-" + UUID.randomUUID());
		faqId = jdbcTemplate.queryForObject("""
				INSERT INTO faq (id, category_id, question, answer, intent, version, created_at, updated_at)
				VALUES (?, ?, ?, 'answer', ?, 1, ?, ?)
				RETURNING id
				""", Long.class,
				uniqueId(),
				categoryId,
				"concurrency test question " + UUID.randomUUID(),
				Intent.GENERAL.name(),
				LocalDateTime.now(),
				LocalDateTime.now());
	}

	private long uniqueId() {
		return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
	}
}
