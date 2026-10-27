package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ubot.guardreview.model.ReviewCheckModels.*;

import com.ubot.guardreview.repository.ReviewCheckRepository;
import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.forbiddenword.service.ForbiddenWordCatalogLock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReviewCheckRepositoryTest {
	JdbcTemplate jdbc = mock(JdbcTemplate.class);
	ReviewCheckRepository checks = new ReviewCheckRepository(jdbc);
	ReviewRepository reviews = new ReviewRepository(jdbc, mock(ForbiddenWordCatalogLock.class));
	String sql() {
		return mockingDetails(jdbc).getInvocations().stream().map(i -> i.getArgument(0, String.class))
				.collect(java.util.stream.Collectors.joining("\n"));
	}
	@Test
	void 출처와_판정을_같은발견에_AND로_적용하고_횟수도_필터링한다() {
		reviews.getCandidatePage("WARNING", 0, 20, Origin.OUTPUT_LLM, Outcome.REJECTED);
		assertThat(sql()).contains("f.guard_origin='OUTPUT_LLM' AND COALESCE(NULLIF(f.guard_outcome,'UNKNOWN')",
				"legacy.source_fingerprint=f.source_fingerprint", "'UNKNOWN')='REJECTED'",
				"EXISTS (SELECT 1 FROM guard_review_findings f WHERE f.candidate_id=c.id",
				"count(*) FROM guard_review_findings f WHERE f.candidate_id=c.id AND f.guard_origin='OUTPUT_LLM'");
	}
	@Test
	void 원문검색_생략기록이_실제판정을_덮어쓰지않는다() {
		checks.saveSkippedSummary(1, "질문", java.util.List.of(Origin.INPUT_LLM, Origin.OUTPUT_CODE, Origin.OUTPUT_LLM));
		assertThat(sql()).contains("WHERE NOT EXISTS", "actual.attempt_id=? AND actual.origin=skipped.origin", "'SKIPPED'", "jsonb_agg(skipped.origin)", "skipped_origins", "ON CONFLICT(capture_key) DO NOTHING");
	}
	@Test
	void 상세원문은_만료즉시_조회에서_숨기고_이후_본문을_정리한다() {
		assertThatThrownBy(() -> checks.detail(1)).isInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
		assertThat(sql()).contains("CASE WHEN details_retained AND created_at>=", "THEN context_snapshot", "days => 30");
		clearInvocations(jdbc);
		checks.expireDetails(100);
		assertThat(sql()).contains("context_snapshot=NULL", "question_snapshot=NULL", "details_retained=false", "LIMIT ?");
	}
	@Test
	void 통과답변필터는_OUTPUT_LLM과_PASS만_동시에_조회한다() {
		checks.page(Origin.OUTPUT_LLM, Outcome.PASS, 0, 20);
		assertThat(sql()).contains("AND (origin=? OR (outcome='SKIPPED' AND skipped_origins @> ?::jsonb)) AND outcome=?", "LIMIT ? OFFSET ?").doesNotContain("outcome<>'REJECTED'");
	}
	@Test
	void 원본질문은_실제시도이력에서_가져오고_없는입력차단은_전달원문을_사용한다() {
		checks.save(UUID.randomUUID(), null, Origin.INPUT_CODE, Outcome.REJECTED, java.util.List.of("INJECTION"),
				"원문", null, null, null, "{}");
		assertThat(sql()).contains("COALESCE((SELECT question FROM answer_attempts_history WHERE attempt_id=?),?)");
	}
	@Test
	void 보류재검토_결과는_해당검사ID의_보존기간내_분석만_조회한다() {
		checks.reassessments(3);
		assertThat(sql()).contains("i.check_id=?", "i.guard_outcome='REVIEW'", "r.details_retained",
				"COALESCE(r.finished_at,r.created_at)>=", "i.reassessment", "ORDER BY i.id DESC LIMIT 10");
	}
}
