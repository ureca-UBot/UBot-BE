package com.ubot.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.common.GlobalException;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.faq.enums.Intent;
import com.ubot.monitoring.dto.model.detail.FaqTrendWindow;
import com.ubot.monitoring.dto.model.detail.FaqUsageTrendPeriod;
import com.ubot.monitoring.dto.model.improvelayer.FaqImprovementType;
import com.ubot.monitoring.repository.FaqMonitoringDetailQueryRepository;
import com.ubot.monitoring.repository.FaqMonitoringRankingQueryRepository;
import com.ubot.monitoring.service.FaqMonitoringService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
		"faq-monitoring.improvement.minimum-top-k-samples=20",
		"faq-monitoring.improvement.minimum-top1-samples=20"
})
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("FAQ 모니터링 통합 테스트")
class FaqMonitoringIntegrationTest {

	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private FaqMonitoringRankingQueryRepository rankingRepository;
	@Autowired private FaqMonitoringDetailQueryRepository detailRepository;
	@Autowired private FaqMonitoringService monitoringService;
	private long userId;

	@BeforeEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM faq_log");
		jdbcTemplate.update("DELETE FROM question_log");
		jdbcTemplate.update("DELETE FROM unanswered_questions");
		jdbcTemplate.update("DELETE FROM unanswered_question_groups");
		jdbcTemplate.update("DELETE FROM answer_attempts_history");
		jdbcTemplate.update("DELETE FROM old_faq");
		jdbcTemplate.update("DELETE FROM faq");
		jdbcTemplate.update("DELETE FROM faq_category");
		userId = jdbcTemplate.queryForObject("""
				INSERT INTO users (email, password_hash, name)
				VALUES (?, 'hash', '테스트 사용자') RETURNING user_id
				""", Long.class, UUID.randomUUID() + "@test.com");
	}

	@Test
	@DisplayName("전체 사용량·유사도 랭킹과 미처리 질문 요약을 기간 기준으로 집계한다")
	void aggregatesRankingAndUnansweredSummary() {
		LocalDateTime now = LocalDateTime.now();
		long categoryId = category();
		long firstFaqId = faq(categoryId, "첫 FAQ", Intent.GENERAL, 1, now.minusDays(10));
		long secondFaqId = faq(categoryId, "둘 FAQ", Intent.GENERAL, 1, now.minusDays(10));
		long thirdFaqId = faq(categoryId, "셋 FAQ", Intent.STORE_DATA, 1, now.minusDays(10));
		long firstQuestionId = question(now.minusHours(3));
		long secondQuestionId = question(now.minusHours(2));
		long thirdQuestionId = question(now.minusHours(1));
		faqLog(firstQuestionId, firstFaqId, 1, 0.90);
		faqLog(firstQuestionId, secondFaqId, 2, 0.80);
		faqLog(secondQuestionId, firstFaqId, 1, 0.85);
		faqLog(thirdQuestionId, thirdFaqId, 1, 0.95);
		pendingUnanswered(now.minusMinutes(30));

		var usage = rankingRepository.findUsageRanking(now.minusDays(1), now.plusDays(1), 20);
		var similarity = rankingRepository.findSimilarityRanking(now.minusDays(1), now.plusDays(1), 20);
		var unanswered = rankingRepository.findUnansweredQuestionSummary(now.minusDays(1), now.plusDays(1));

		assertThat(usage.all().top().getFirst().faqId()).isEqualTo(firstFaqId);
		assertThat(usage.all().top().getFirst().count()).isEqualTo(2);
		assertThat(usage.storeData().top().getFirst().faqId()).isEqualTo(thirdFaqId);
		assertThat(similarity.all().top().getFirst().faqId()).isEqualTo(thirdFaqId);
		assertThat(similarity.overallAverageSimilarity()).isEqualTo(0.875);
		assertThat(unanswered.totalQuestionCount()).isEqualTo(3);
		assertThat(unanswered.unansweredQuestionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("상세 지표는 달력 버킷, 버전, Similarity, Top-1, Gap, 함께 등장한 FAQ를 집계한다")
	void aggregatesFaqDetailMetrics() {
		LocalDateTime now = LocalDateTime.now().withDayOfMonth(20);
		long categoryId = category();
		long targetFaqId = faq(categoryId, "대상 FAQ", Intent.GENERAL, 2, now.minusDays(2));
		long otherFaqId = faq(categoryId, "함께 등장 FAQ", Intent.GENERAL, 1, now.minusDays(10));
		jdbcTemplate.update("INSERT INTO old_faq (faq_id, version, category_id, question, answer, intent, updated_at) VALUES (?, 1, ?, '대상 FAQ', '답변', 'GENERAL', ?)",
				targetFaqId, categoryId, now.minusDays(20));
		long oldQuestionId = question(now.minusDays(10));
		long recentQuestionId = question(now.minusDays(1));
		faqLog(oldQuestionId, targetFaqId, 1, 0.90);
		faqLog(oldQuestionId, otherFaqId, 2, 0.85);
		faqLog(recentQuestionId, targetFaqId, 1, 0.80);
		faqLog(recentQuestionId, otherFaqId, 2, 0.70);
		FaqTrendWindow window = FaqTrendWindow.from(FaqUsageTrendPeriod.MONTH, now, now);

		var trend = detailRepository.findUsageTrend(targetFaqId, window, now);
		var similarity = detailRepository.findSimilaritySummary(targetFaqId, window);
		var top1 = detailRepository.findTop1Summary(targetFaqId, window);
		var gap = detailRepository.findTopGapSummary(targetFaqId, window);
		var cooccurring = detailRepository.findCooccurringFaqs(targetFaqId, window);

		assertThat(trend).hasSize(now.toLocalDate().lengthOfMonth());
		assertThat(trend.stream().filter(point -> point.count() > 0).map(point -> point.faqVersion()))
				.contains(1, 2);
		assertThat(similarity.averageSimilarity()).isBetween(0.849, 0.851);
		assertThat(top1.top1Count()).isEqualTo(2);
		assertThat(gap.averageGap()).isBetween(0.074, 0.076);
		assertThat(cooccurring).singleElement().extracting(item -> item.faqId()).isEqualTo(otherFaqId);
	}

	@Test
	@DisplayName("개선 목록은 현재 버전의 낮은 Similarity·후보 혼동·미사용 FAQ만 반환한다")
	void findsCurrentVersionImprovementFaqs() {
		LocalDateTime now = LocalDateTime.now();
		long categoryId = category();
		long qualityFaqId = faq(categoryId, "품질 개선 FAQ", Intent.GENERAL, 2, now.minusDays(10));
		long competingFaqId = faq(categoryId, "경쟁 FAQ", Intent.GENERAL, 1, now.minusDays(10));
		long unusedFaqId = faq(categoryId, "미사용 FAQ", Intent.STORE_DATA, 1, now.minusDays(8));
		long previousVersionQuestionId = question(now.minusDays(11));
		faqLog(previousVersionQuestionId, qualityFaqId, 1, 0.99);
		faqLog(previousVersionQuestionId, competingFaqId, 2, 0.90);
		for (int index = 0; index < 20; index++) {
			long questionId = question(now.minusHours(index + 1));
			faqLog(questionId, qualityFaqId, 1, 0.79);
			faqLog(questionId, competingFaqId, 2, 0.77);
		}

		var items = monitoringService.getFaqImprovements().items();
		var qualityItem = items.stream().filter(item -> item.faqId().equals(qualityFaqId)).findFirst().orElseThrow();
		var unusedItem = items.stream().filter(item -> item.faqId().equals(unusedFaqId)).findFirst().orElseThrow();

		assertThat(qualityItem.improvementTypes())
				.contains(FaqImprovementType.LOW_RAG_QUALITY, FaqImprovementType.DUPLICATE_SUSPECTED);
		assertThat(qualityItem.topKCount()).isEqualTo(20);
		assertThat(unusedItem.improvementTypes()).containsExactly(FaqImprovementType.LOW_USAGE);
	}

	@Test
	@DisplayName("전체 모니터링 기간의 시작 시각이 종료 시각과 같거나 늦으면 잘못된 파라미터로 거절한다")
	void rejectsInvalidMonitoringPeriod() {
		LocalDateTime now = LocalDateTime.now();

		assertThatThrownBy(() -> monitoringService.getMonitoringData(now, now))
				.isInstanceOfSatisfying(GlobalException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
		assertThatThrownBy(() -> monitoringService.getMonitoringData(now.plusHours(1), now))
				.isInstanceOfSatisfying(GlobalException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
	}

	private long category() {
		return jdbcTemplate.queryForObject("INSERT INTO faq_category (name) VALUES (?) RETURNING id", Long.class,
				"카테고리-" + UUID.randomUUID());
	}

	private long faq(long categoryId, String question, Intent intent, int version, LocalDateTime updatedAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO faq (category_id, question, answer, intent, version, created_at, updated_at)
				VALUES (?, ?, '답변', ?, ?, ?, ?) RETURNING id
				""", Long.class, categoryId, question, intent.name(), version, updatedAt, updatedAt);
	}

	private long question(LocalDateTime createdAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO question_log (user_id, user_question, llm_question, normalized_question, ranking_eligible, created_at)
				VALUES (?, '질문', '답변', '질문', false, ?) RETURNING id
				""", Long.class, userId, createdAt);
	}

	private void faqLog(long questionLogId, long faqId, int rank, double similarity) {
		jdbcTemplate.update("INSERT INTO faq_log (question_log_id, faq_id, rank, similarity) VALUES (?, ?, ?, ?)",
				questionLogId, faqId, rank, similarity);
	}

	private void pendingUnanswered(LocalDateTime createdAt) {
		long attemptId = jdbcTemplate.queryForObject("""
				INSERT INTO answer_attempts_history (user_id, question, attempt_count, status, idempotency_key, created_at)
				VALUES (?, '미응답 질문', 1, 'FAIL', ?, ?) RETURNING attempt_id
				""", Long.class, userId, UUID.randomUUID().toString(), createdAt);
		long groupId = jdbcTemplate.queryForObject("""
				INSERT INTO unanswered_question_groups (representative_question, question_count, status, last_occurred_at)
				VALUES ('미응답 질문', 1, 'PENDING', ?) RETURNING id
				""", Long.class, createdAt);
		jdbcTemplate.update("""
				INSERT INTO unanswered_questions (attempt_id, group_id, question, reason, created_at)
				VALUES (?, ?, '미응답 질문', 'NO_FAQ', ?)
				""", attemptId, groupId, createdAt);
	}
}
