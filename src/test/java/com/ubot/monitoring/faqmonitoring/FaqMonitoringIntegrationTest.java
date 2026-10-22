package com.ubot.monitoring.faqmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ubot.PgvectorTestConfiguration;
import com.ubot.common.GlobalException;
import com.ubot.common.exception.CommonErrorCode;
import com.ubot.faq.enums.Intent;
import com.ubot.monitoring.faqmonitoring.dto.model.detail.FaqTrendWindow;
import com.ubot.monitoring.faqmonitoring.dto.model.detail.FaqUsageTrendPeriod;
import com.ubot.monitoring.faqmonitoring.dto.model.improvelayer.FaqImprovementType;
import com.ubot.monitoring.faqmonitoring.dto.request.FaqImprovementExclusionRequest;
import com.ubot.monitoring.faqmonitoring.repository.FaqMonitoringDetailQueryRepository;
import com.ubot.monitoring.faqmonitoring.repository.FaqImprovementExclusionRepository;
import com.ubot.monitoring.faqmonitoring.repository.FaqMonitoringRankingQueryRepository;
import com.ubot.monitoring.faqmonitoring.service.FaqMonitoringService;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
		"faq-monitoring.improvement.minimum-top-k-samples=20",
		"faq-monitoring.improvement.minimum-top1-samples=20",
		"faq-monitoring.improvement.low-gap-threshold=0.03"
})
@AutoConfigureMockMvc(addFilters = false)
@Import(PgvectorTestConfiguration.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("FAQ 모니터링 통합 테스트")
class FaqMonitoringIntegrationTest {

	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private MockMvc mockMvc;
	@Autowired private FaqMonitoringRankingQueryRepository rankingRepository;
	@Autowired private FaqMonitoringDetailQueryRepository detailRepository;
	@Autowired private FaqMonitoringService monitoringService;
	@Autowired private FaqImprovementExclusionRepository exclusionRepository;
	@Autowired private EntityManager entityManager;
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
		long oldOnlyFaqId = faq(categoryId, "과거 버전만 사용된 FAQ", Intent.STORE_DATA, 2, now.minusDays(10));
		long previousVersionQuestionId = question(now.minusDays(11));
		faqLog(previousVersionQuestionId, qualityFaqId, 1, 0.99);
		faqLog(previousVersionQuestionId, competingFaqId, 2, 0.90);
		faqLog(question(now.minusDays(11)), oldOnlyFaqId, 1, 0.95);
		for (int index = 0; index < 20; index++) {
			long questionId = question(now.minusHours(index + 1));
			faqLog(questionId, qualityFaqId, 1, 0.79);
			faqLog(questionId, competingFaqId, 2, 0.77);
		}

		var items = monitoringService.getFaqImprovements(null, 0, Integer.MAX_VALUE).items();
		var qualityItem = items.stream().filter(item -> item.faqId().equals(qualityFaqId)).findFirst().orElseThrow();
		var unusedItem = items.stream().filter(item -> item.faqId().equals(unusedFaqId)).findFirst().orElseThrow();
		var oldOnlyItem = items.stream().filter(item -> item.faqId().equals(oldOnlyFaqId)).findFirst().orElseThrow();

		assertThat(qualityItem.improvementTypes())
				.contains(FaqImprovementType.LOW_RAG_QUALITY, FaqImprovementType.DUPLICATE_SUSPECTED);
		assertThat(qualityItem.topKCount()).isEqualTo(20);
		assertThat(unusedItem.improvementTypes()).containsExactly(FaqImprovementType.LOW_USAGE);
		assertThat(oldOnlyItem.improvementTypes()).containsExactly(FaqImprovementType.LOW_USAGE);
		assertThat(oldOnlyItem.topKCount()).isZero();
	}

	@Test
	@DisplayName("개선 유형은 모두 포함해야 하며 필터링된 목록을 페이지 단위로 반환한다")
	void filtersAndPaginatesImprovementFaqs() throws Exception {
		LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
		long categoryId = category();
		long bothFaqId = faq(categoryId, "품질 저하 및 중복 의심 FAQ", Intent.GENERAL, 1, now.minusDays(1));
		long qualityOnlyFaqId = faq(categoryId, "품질 저하 FAQ", Intent.GENERAL, 1, now.minusDays(1));
		long firstUnusedFaqId = faq(categoryId, "미사용 FAQ 1", Intent.STORE_DATA, 1, now.minusDays(8));
		long secondUnusedFaqId = faq(categoryId, "미사용 FAQ 2", Intent.STORE_DATA, 1, now.minusDays(8));
		for (int index = 0; index < 20; index++) {
			long questionId = question(now.minusMinutes(1));
			faqLog(questionId, bothFaqId, 1, 0.79);
			faqLog(questionId, qualityOnlyFaqId, 2, 0.77);
		}

		var filtered = monitoringService.getFaqImprovements(
				List.of(FaqImprovementType.LOW_RAG_QUALITY, FaqImprovementType.DUPLICATE_SUSPECTED), 0, 10);
		assertThat(filtered.totalElements()).isEqualTo(1);
		assertThat(filtered.items()).singleElement().satisfies(item -> assertThat(item.faqId()).isEqualTo(bothFaqId));

		var firstPage = monitoringService.getFaqImprovements(List.of(FaqImprovementType.LOW_USAGE), 0, 1);
		var secondPage = monitoringService.getFaqImprovements(List.of(FaqImprovementType.LOW_USAGE), 1, 1);
		var emptyPage = monitoringService.getFaqImprovements(List.of(FaqImprovementType.LOW_USAGE), 2, 1);
		assertThat(firstPage.totalElements()).isEqualTo(2);
		assertThat(firstPage.totalPages()).isEqualTo(2);
		assertThat(firstPage.first()).isTrue();
		assertThat(firstPage.last()).isFalse();
		assertThat(firstPage.items().getFirst().faqId()).isEqualTo(firstUnusedFaqId);
		assertThat(secondPage.items().getFirst().faqId()).isEqualTo(secondUnusedFaqId);
		assertThat(secondPage.last()).isTrue();
		assertThat(emptyPage.items()).isEmpty();
		assertThat(emptyPage.totalElements()).isEqualTo(2);

		mockMvc.perform(get("/admin/faq-monitoring/improvements")
					.param("types", "LOW_RAG_QUALITY", "DUPLICATE_SUSPECTED")
					.param("page", "0")
					.param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalElements").value(1))
				.andExpect(jsonPath("$.data.items[0].question").value("품질 저하 및 중복 의심 FAQ"));
	}

	@Test
	@DisplayName("같은 FAQ 버전의 개선 제외 항목은 개선 목록에서 숨겨진다")
	void hidesExcludedImprovementForSameFaqVersion() {
		long faqId = unusedFaq();

		monitoringService.excludeFaqImprovement(
				faqId, new FaqImprovementExclusionRequest(FaqImprovementType.LOW_USAGE, "제외"), userId);
		exclusionRepository.flush();

		assertThat(lowUsageImprovementIds()).doesNotContain(faqId);
		assertThat(monitoringService.getFaqImprovementExclusions(0, 10).content())
				.singleElement()
				.satisfies(exclusion -> {
					assertThat(exclusion.faqId()).isEqualTo(faqId);
					assertThat(exclusion.outdated()).isFalse();
				});
	}

	@Test
	@DisplayName("FAQ 버전이 오르면 기존 활성 제외는 오래된 이력이 되고 개선 목록에 다시 나타난다")
	void showsImprovementAgainWhenFaqVersionChanges() {
		long faqId = unusedFaq();
		monitoringService.excludeFaqImprovement(
				faqId, new FaqImprovementExclusionRequest(FaqImprovementType.LOW_USAGE, null), userId);
		exclusionRepository.flush();
		assertThat(lowUsageImprovementIds()).doesNotContain(faqId);

		jdbcTemplate.update("UPDATE faq SET version = version + 1 WHERE id = ?", faqId);
		entityManager.clear();

		assertThat(lowUsageImprovementIds()).contains(faqId);
		assertThat(monitoringService.getFaqImprovementExclusions(0, 10).content())
				.singleElement()
				.extracting(exclusion -> exclusion.outdated())
				.isEqualTo(true);
	}

	@Test
	@DisplayName("개선 제외를 해제하면 목록에 다시 나타나고 이력 목록으로 이동한다")
	void showsImprovementAgainWhenExclusionIsReleased() {
		long faqId = unusedFaq();
		monitoringService.excludeFaqImprovement(
				faqId, new FaqImprovementExclusionRequest(FaqImprovementType.LOW_USAGE, null), userId);
		exclusionRepository.flush();
		long exclusionId = monitoringService.getFaqImprovementExclusions(0, 10).content().getFirst().exclusionId();

		monitoringService.releaseFaqImprovementExclusion(exclusionId, userId);
		exclusionRepository.flush();

		assertThat(lowUsageImprovementIds()).contains(faqId);
		assertThat(monitoringService.getFaqImprovementExclusions(0, 10).content()).isEmpty();
		assertThat(monitoringService.getFaqImprovementExclusionHistory(0, 10).content())
				.singleElement()
				.extracting(exclusion -> exclusion.exclusionId())
				.isEqualTo(exclusionId);
	}

	@Test
	@DisplayName("활성 상태의 개선 제외 이력을 삭제하면 개선 목록에 다시 나타난다")
	void showsImprovementAgainWhenActiveExclusionIsDeleted() {
		long faqId = unusedFaq();
		monitoringService.excludeFaqImprovement(
				faqId, new FaqImprovementExclusionRequest(FaqImprovementType.LOW_USAGE, null), userId);
		exclusionRepository.flush();
		long exclusionId = monitoringService.getFaqImprovementExclusions(0, 10).content().getFirst().exclusionId();

		monitoringService.deleteFaqImprovementExclusion(exclusionId);
		exclusionRepository.flush();

		assertThat(monitoringService.getFaqImprovementExclusions(0, 10).content()).isEmpty();
		assertThat(lowUsageImprovementIds()).contains(faqId);
	}

	@Test
	@DisplayName("같은 개선 제외를 반복 등록해도 활성 제외는 하나만 유지된다")
	void ignoresDuplicateExclusionRequests() {
		long faqId = unusedFaq();
		FaqImprovementExclusionRequest request =
				new FaqImprovementExclusionRequest(FaqImprovementType.LOW_USAGE, null);

		monitoringService.excludeFaqImprovement(faqId, request, userId);
		monitoringService.excludeFaqImprovement(faqId, request, userId);
		exclusionRepository.flush();

		assertThat(monitoringService.getFaqImprovementExclusions(0, 10).content())
				.singleElement()
				.extracting(exclusion -> exclusion.faqId())
				.isEqualTo(faqId);
		assertThat(lowUsageImprovementIds()).doesNotContain(faqId);
	}

	@Test
	@DisplayName("LOW_RAG_QUALITY를 제외해도 같은 FAQ의 DUPLICATE_SUSPECTED는 개선 목록에 남는다")
	void keepsDuplicateSuspectedVisibleWhenLowRagQualityIsExcluded() {
		long faqId = faqWithLowRagQualityAndDuplicateSuspected();

		monitoringService.excludeFaqImprovement(
				faqId, new FaqImprovementExclusionRequest(FaqImprovementType.LOW_RAG_QUALITY, null), userId);
		exclusionRepository.flush();

		assertThat(improvementTypesOf(faqId)).containsExactly(FaqImprovementType.DUPLICATE_SUSPECTED);
	}

	@Test
	@DisplayName("DUPLICATE_SUSPECTED를 제외해도 같은 FAQ의 LOW_RAG_QUALITY는 개선 목록에 남는다")
	void keepsLowRagQualityVisibleWhenDuplicateSuspectedIsExcluded() {
		long faqId = faqWithLowRagQualityAndDuplicateSuspected();

		monitoringService.excludeFaqImprovement(
				faqId, new FaqImprovementExclusionRequest(FaqImprovementType.DUPLICATE_SUSPECTED, null), userId);
		exclusionRepository.flush();

		assertThat(improvementTypesOf(faqId)).containsExactly(FaqImprovementType.LOW_RAG_QUALITY);
	}

	@Test
	@DisplayName("현재 버전에서 7일보다 오래된 사용 기록이 있어도 LOW_USAGE로 분류하지 않는다")
	void doesNotClassifyFaqWithExpiredCurrentVersionUsageAsLowUsage() {
		LocalDateTime now = LocalDateTime.now();
		long faqId = faq(category(), "오래된 사용 기록 FAQ", Intent.GENERAL, 1, now.minusDays(10));
		faqLog(question(now.minusDays(8)), faqId, 1, 0.95);

		assertThat(lowUsageImprovementIds()).doesNotContain(faqId);
	}

	@Test
	@DisplayName("중복 후보는 현재 버전의 1위·2위 유사도 차이와 후보별 횟수로 조회한다")
	void findsDuplicateCandidatesAndCounts() throws Exception {
		LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
		long categoryId = category();
		long targetFaqId = faq(categoryId, "대상 FAQ", Intent.GENERAL, 1, now.minusDays(1));
		long firstCandidateId = faq(categoryId, "첫 후보 FAQ", Intent.GENERAL, 2, now.minusHours(1));
		long secondCandidateId = faq(categoryId, "둘째 후보 FAQ", Intent.GENERAL, 1, now.minusDays(3));
		long oldCandidateId = faq(categoryId, "이전 버전 후보 FAQ", Intent.GENERAL, 2, now.minusMinutes(5));
		for (int index = 0; index < 2; index++) {
			long pairedQuestionId = question(now.minusMinutes(10 - index));
			faqLog(pairedQuestionId, targetFaqId, 1, 0.90);
			faqLog(pairedQuestionId, firstCandidateId, 2, 0.88);
			long reversedQuestionId = question(now.minusMinutes(3 - index));
			faqLog(reversedQuestionId, firstCandidateId, 1, 0.95);
			faqLog(reversedQuestionId, targetFaqId, 2, 0.94);
		}
		long secondCandidateQuestionId = question(now.minusMinutes(8));
		faqLog(secondCandidateQuestionId, targetFaqId, 1, 0.91);
		faqLog(secondCandidateQuestionId, secondCandidateId, 2, 0.90);
		long wideGapQuestionId = question(now.minusMinutes(7));
		faqLog(wideGapQuestionId, targetFaqId, 1, 0.90);
		faqLog(wideGapQuestionId, secondCandidateId, 2, 0.86);
		long oldCandidateQuestionId = question(now.minusMinutes(10));
		faqLog(oldCandidateQuestionId, targetFaqId, 1, 0.90);
		faqLog(oldCandidateQuestionId, oldCandidateId, 2, 0.89);
		long oldTargetQuestionId = question(now.minusDays(2));
		faqLog(oldTargetQuestionId, targetFaqId, 1, 0.90);
		faqLog(oldTargetQuestionId, secondCandidateId, 2, 0.89);

		var candidates = monitoringService.getDuplicateCandidates(targetFaqId);
		assertThat(candidates).hasSize(2);
		assertThat(candidates.get(0).faqId()).isEqualTo(firstCandidateId);
		assertThat(candidates.get(0).pairCount()).isEqualTo(2);
		assertThat(candidates.get(0).top1Count()).isEqualTo(2);
		assertThat(candidates.get(1).faqId()).isEqualTo(secondCandidateId);
		assertThat(candidates.get(1).pairCount()).isEqualTo(1);
		assertThat(candidates.get(1).top1Count()).isZero();
		assertThat(monitoringService.getDuplicateCandidates(secondCandidateId)).isEmpty();

		mockMvc.perform(get("/admin/faq-monitoring/improvements/{faqId}/duplicates", targetFaqId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].question").value("첫 후보 FAQ"))
				.andExpect(jsonPath("$.data[0].top1Count").value(2))
				.andExpect(jsonPath("$.data[0].pairCount").value(2));
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

	private long unusedFaq() {
		return faq(category(), "제외 테스트 FAQ", Intent.GENERAL, 1, LocalDateTime.now().minusDays(8));
	}

	private List<Long> lowUsageImprovementIds() {
		return monitoringService.getFaqImprovements(List.of(FaqImprovementType.LOW_USAGE), 0, 10).items().stream()
				.map(item -> item.faqId())
				.toList();
	}

	private long faqWithLowRagQualityAndDuplicateSuspected() {
		LocalDateTime now = LocalDateTime.now();
		long categoryId = category();
		long faqId = faq(categoryId, "낮은 품질·중복 의심 FAQ", Intent.GENERAL, 1, now.minusDays(1));
		long competingFaqId = faq(categoryId, "경쟁 FAQ", Intent.GENERAL, 1, now.minusDays(1));
		for (int index = 0; index < 20; index++) {
			long questionId = question(now.minusMinutes(index + 1));
			faqLog(questionId, faqId, 1, 0.70);
			faqLog(questionId, competingFaqId, 2, 0.68);
		}
		return faqId;
	}

	private List<FaqImprovementType> improvementTypesOf(long faqId) {
		return monitoringService.getFaqImprovements(null, 0, 10).items().stream()
				.filter(item -> item.faqId().equals(faqId))
				.findFirst()
				.orElseThrow()
				.improvementTypes();
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
				INSERT INTO unanswered_questions (attempt_id, group_id, sub_question, user_text, reason, created_at)
				VALUES (?, ?, '미응답 질문', '미응답 질문', 'NO_FAQ', ?)
				""", attemptId, groupId, createdAt);
	}
}
