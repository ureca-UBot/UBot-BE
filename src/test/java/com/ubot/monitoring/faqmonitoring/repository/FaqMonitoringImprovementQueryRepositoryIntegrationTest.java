package com.ubot.monitoring.faqmonitoring.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.faq.enums.Intent;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@Transactional
class FaqMonitoringImprovementQueryRepositoryIntegrationTest {

	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private FaqMonitoringImprovementQueryRepository repository;

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
				VALUES (?, 'hash', 'AI 검토 테스트 사용자') RETURNING user_id
				""", Long.class, UUID.randomUUID() + "@test.com");
	}

	@Test
	@DisplayName("검색 품질 지표를 계산할 때 익명화된 로그는 검토 표본과 평균에서 제외한다")
	void 검색품질_지표는_익명화된_로그를_표본과_평균에서_제외한다() {
		LocalDateTime now = LocalDateTime.now();
		long faqId = faq(category(), "포인트 잔액", now.minusDays(8));
		for (int index = 0; index < 20; index++) {
			faqLog(question("잔액 질문 " + index, null, now.minusMinutes(index + 1), null), faqId, 1, 0.70);
		}
		for (int index = 0; index < 2; index++) {
			LocalDateTime createdAt = now.minusHours(index + 1);
			faqLog(question("익명화 질문 " + index, null, createdAt, createdAt.plusMinutes(1)), faqId, 1, 0.99);
		}
		var criteria = new FaqImprovementSearchCriteria(
				now.plusSeconds(1), now.minusDays(7), 3, 0.07, 0.76, 0.40,
				20, 20, null, 0, 10);

		var item = repository.findImprovements(criteria).content().stream()
				.filter(candidate -> candidate.faqId().equals(faqId))
				.findFirst()
				.orElseThrow();

		assertThat(item.improvementTypes()).contains(FaqImprovementType.LOW_RAG_QUALITY);
		assertThat(item.topKCount()).isEqualTo(22);
		assertThat(item.reviewableTopKCount()).isEqualTo(20);
		assertThat(item.averageSimilarity()).isBetween(0.699, 0.701);
	}

	@Test
	@DisplayName("검색 품질 검토 질문은 최저 유사도순으로, 화면 표시 질문은 최근순으로 조회한다")
	void 검색품질_질문은_검토용은_최저_유사도순이고_표시용은_최근순이다() {
		LocalDateTime now = LocalDateTime.now();
		long faqId = faq(category(), "멤버십 포인트", now.minusDays(2));
		faqLog(question("반복 질문", null, now.minusHours(3), null), faqId, 1, 0.61);
		faqLog(question("다른 질문", "분리된 다른 질문", now.minusHours(2), null), faqId, 1, 0.73);
		faqLog(question("반복 질문", null, now.minusHours(1), null), faqId, 1, 0.82);
		LocalDateTime anonymizedAt = now.minusMinutes(20);
		faqLog(question("익명화 질문", null, now.minusMinutes(30), anonymizedAt), faqId, 1, 0.50);

		var reviewQuestions = repository.findLowRagQualityQuestions(faqId, now, 3, 20);
		var recentQuestions = repository.findRecentLowRagQualityQuestions(faqId, now, 3, 5);

		assertThat(reviewQuestions)
				.extracting(question -> question.question())
				.containsExactly("반복 질문", "분리된 다른 질문");
		assertThat(reviewQuestions.getFirst().similarity()).isEqualTo(0.61);
		assertThat(recentQuestions)
				.extracting(question -> question.question())
				.containsExactly("반복 질문", "분리된 다른 질문");
		assertThat(recentQuestions.getFirst().similarity()).isEqualTo(0.82);
	}

	@Test
	@DisplayName("중복 근거는 임계값 안의 비익명 로그만 집계하고 질문은 중복 제거 후 최근순으로 반환한다")
	void 중복_근거는_임계값_안의_익명화되지_않은_로그만_세고_질문은_중복을_제거해_최근순으로_반환한다() {
		LocalDateTime now = LocalDateTime.now();
		long categoryId = category();
		long targetFaqId = faq(categoryId, "유심 재발급", now.minusDays(2));
		long candidateFaqId = faq(categoryId, "매장 유심 재발급", now.minusDays(2));

		pairedQuestion(targetFaqId, candidateFaqId,
				question("질문 B", null, now.minusHours(3), null), 0.90, 0.88);
		pairedQuestion(targetFaqId, candidateFaqId,
				question("질문 A", null, now.minusHours(2), null), 0.91, 0.89);
		pairedQuestion(targetFaqId, candidateFaqId,
				question("질문 A", null, now.minusHours(1), null), 0.92, 0.90);
		pairedQuestion(targetFaqId, candidateFaqId,
				question("간격이 큰 질문", null, now.minusMinutes(30), null), 0.90, 0.70);
		LocalDateTime anonymizedAt = now.minusMinutes(10);
		pairedQuestion(targetFaqId, candidateFaqId,
				question("익명화 질문", null, now.minusMinutes(20), anonymizedAt), 0.90, 0.89);

		var evidence = repository.findDuplicateEvidence(
				targetFaqId, List.of(candidateFaqId), now, 0.07, 5);

		assertThat(evidence).singleElement().satisfies(item -> {
			assertThat(item.candidateFaqId()).isEqualTo(candidateFaqId);
			assertThat(item.pairCount()).isEqualTo(3);
			assertThat(item.questions()).containsExactly("질문 A", "질문 B");
		});
	}

	private long category() {
		return jdbcTemplate.queryForObject(
				"INSERT INTO faq_category (name) VALUES (?) RETURNING id",
				Long.class, "AI 검토 카테고리-" + UUID.randomUUID());
	}

	private long faq(long categoryId, String question, LocalDateTime updatedAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO faq (category_id, question, answer, intent, version, created_at, updated_at)
				VALUES (?, ?, '테스트 답변', ?, 1, ?, ?) RETURNING id
				""", Long.class, categoryId, question, Intent.GENERAL.name(), updatedAt, updatedAt);
	}

	private long question(
			String userQuestion,
			String subQuestion,
			LocalDateTime createdAt,
			LocalDateTime anonymizedAt
	) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO question_log (
					user_id, user_question, sub_question, llm_question, normalized_question,
					ranking_eligible, anonymized_at, created_at
				)
				VALUES (?, ?, ?, '답변', ?, false, ?, ?) RETURNING id
				""", Long.class, userId, userQuestion, subQuestion, userQuestion, anonymizedAt, createdAt);
	}

	private void pairedQuestion(
			long targetFaqId,
			long candidateFaqId,
			long questionId,
			double targetSimilarity,
			double candidateSimilarity
	) {
		faqLog(questionId, targetFaqId, 1, targetSimilarity);
		faqLog(questionId, candidateFaqId, 2, candidateSimilarity);
	}

	private void faqLog(long questionLogId, long faqId, int rank, double similarity) {
		jdbcTemplate.update(
				"INSERT INTO faq_log (question_log_id, faq_id, rank, similarity) VALUES (?, ?, ?, ?)",
				questionLogId, faqId, rank, similarity);
	}
}
