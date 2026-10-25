package com.ubot.guardreview.service;

import static com.ubot.guardreview.model.ReviewModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ubot.forbiddenword.service.ForbiddenWordCatalogLock;
import com.ubot.guardreview.repository.ReviewRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 외부 DB 없이 실제 저장소가 넘기는 쿼리의 범위·중복키·프로필 조건을 검증합니다. */
class ReviewRepositoryTest {
	JdbcTemplate jdbc = mock(JdbcTemplate.class);
	ReviewRepository repository = new ReviewRepository(jdbc, mock(ForbiddenWordCatalogLock.class));
	String sql() {
		return mockingDetails(jdbc).getInvocations().stream().map(i -> i.getArgument(0, String.class))
				.collect(java.util.stream.Collectors.joining("\n"));
	}
	@Test
	void 의미묶기는_같은위험유형_의도_모델프로필로_한정한다() {
		float[] values = new float[1024];
		values[0] = 1;
		repository.similar(new Suggestion(Kind.WARNING, "FRAUD", "위험 요구", "검토", Stage.INPUT, "HARMFUL_REQUEST"),
				new Vector("profile-1", values), .97);
		assertThat(sql()).contains("kind='WARNING'", "category=?", "intent=?", "embedding_profile=?");
	}
	@Test
	void 보류선택_스냅샷은_출처_판정_ID_기간을_AND로_고정하고_정리와_경합하지않는다() {
		repository.snapshotReviewSources(7, com.ubot.guardreview.model.ReviewCheckModels.Origin.INPUT_LLM,
				java.util.List.of(1L, 2L));
		assertThat(sql()).contains("g.origin=? AND g.outcome='REVIEW'", "g.id IN (?,?)", "g.details_retained",
				"g.created_at<=", "g.created_at>=LOCALTIMESTAMP", "days => 30", "FOR SHARE OF g",
				"original_guard_reasons", "original_guard_explanation");
		verify(jdbc).update(contains("g.id IN"), eq(7L), eq("INPUT_LLM"), eq(1L), eq(2L), eq(7L));
		assertThat(sql()).doesNotContain("UPDATE guard_review_checks", "FROM answer_attempts_history");
	}
	@Test
	void 보류재검토_의견은_원래판정과_별도로_저장하고_상세만료시_지운다() {
		repository.saveReassessment(1, new com.ubot.guardreview.model.ReviewReassessment(
				com.ubot.guardreview.model.ReviewReassessment.Verdict.NORMAL_POSSIBLE, "검토 의견", java.util.List.of()));
		assertThat(sql()).contains("reassessment=?::jsonb", "status='COMPLETED'").doesNotContain("guard_review_checks");
		clearInvocations(jdbc);
		repository.clearSourceSnapshots(100);
		assertThat(sql()).contains("reassessment=NULL", "i.reassessment IS NOT NULL", "NOT r.details_retained");
	}
	@Test
	void 재분석_경고_중복은_벡터가_아닌_원본과_유형으로_확인한다() {
		repository.existingWarning(new Source(1, "original", "질문", null),
				new Suggestion(Kind.WARNING, "FRAUD", "질문", "검토", Stage.INPUT, "HARMFUL_REQUEST"));
		assertThat(sql()).contains("f.source_key=?", "f.stage=?", "f.category=?", "c.kind='WARNING'")
				.doesNotContain("<=>");
	}
	@Test
	void 동시_작업은_건너뛰고_만료된_lease만_재접수한다() {
		repository.claim(UUID.randomUUID());
		assertThat(sql()).contains("FOR UPDATE SKIP LOCKED", "lease_until < LOCALTIMESTAMP", "lease_token=?");
	}
	@Test
	void 스냅샷은_가드전_수집응답만_읽고_최종답변이나_질문전용기록은_읽지않는다() {
		repository.snapshotSources(7, AnalysisMode.FULL);
		assertThat(sql())
				.contains("s.run_id IS NULL", "s.capture_id IS NOT NULL", "s.guard_outcome<>'PENDING'",
						"ON CONFLICT(run_id,source_key) DO NOTHING", "s.updated_at <=", "s.final_answer_snapshot", "s.guard_stage")
				.doesNotContain("question_log", "FROM answer_attempts_history");
	}
	@Test
	void 검사_분석과_보류_재검토는_기존_해시_제약에_맞는_같은_중복키를_사용한다() {
		String hashedKey = "'sha256:'||encode(sha256(convert_to('guard-check:'||g.id,'UTF8')),'hex')";
		repository.snapshotSources(7, AnalysisMode.INCREMENTAL, AnalysisSource.INPUT_LLM);
		assertThat(sql()).contains(hashedKey, "previous.source_key=g.source_key", "g.origin=?", "g.outcome IN ('PASS','FILTERED','REJECTED')");
		clearInvocations(jdbc);
		repository.snapshotReviewSources(8, com.ubot.guardreview.model.ReviewCheckModels.Origin.INPUT_LLM,
				java.util.List.of(1L));
		assertThat(sql()).contains("g.attempt_id," + hashedKey);
	}
	@Test
	void 원본질문은_시도이력에서_읽고_같은수집키의_재저장은_중복생성하지않는다() {
		assertThatThrownBy(() -> repository.captureResponse(UUID.randomUUID(), 3, "답변"))
				.isInstanceOf(com.ubot.guardreview.exception.ReviewException.class);
		assertThat(sql()).contains("a.question", "a.attempt_id=?", "ON CONFLICT(capture_id)");
	}
	@Test
	void 후보목록은_고정200건_대신_요청페이지로_조회한다() {
		repository.getCandidatePage("WORD", 10, 20);
		var queries = mockingDetails(jdbc).getInvocations().stream().map(i -> i.getArgument(0, String.class)).toList();
		assertThat(queries).anySatisfy(q -> assertThat(q).contains("count(*)"));
		assertThat(queries).anySatisfy(q -> assertThat(q).contains("LIMIT ? OFFSET ?").doesNotContain("LIMIT 200"));
	}
	@Test
	void 증분기준은_최신_성공_내용이며_실패와_단순시각은_아니다() {
		repository.snapshotSources(7, AnalysisMode.INCREMENTAL);
		assertThat(sql()).contains("s.source_fingerprint", "previous.run_id IS NOT NULL", "previous.status='COMPLETED'",
				"ORDER BY previous.finished_at DESC,previous.id DESC LIMIT 1", "IS DISTINCT FROM", "?='FULL'");
	}
	@Test
	void 분석중_원본변경은_동결한_자료를_바꾸지않는다() {
		repository.nextSource(7);
		assertThat(sql()).contains("question_snapshot", "answer_snapshot").doesNotContain("question_log",
				"answer_attempts_history");
	}
	@Test
	void 전체재분석은_중복집계하지않고_실제내용변경은_구분한다() {
		repository.finding(2, 7, new Source(1, "source", "질문", null),
				new Suggestion(Kind.WARNING, "FRAUD", "질문", "검토", Stage.INPUT, "HARMFUL_REQUEST"));
		assertThat(sql()).contains("ON CONFLICT(candidate_id,source_key,source_fingerprint,stage,category) DO NOTHING");
	}
	@Test
	void 상세정리는_종료기록만_만료시키고_검사결과_요약은_남긴다() {
		repository.expireRunDetails(1000);
		assertThat(sql()).contains("status IN ('COMPLETED','FAILED')", "result=result-'cases'",
				"details_retained=false", "LIMIT ?", "FOR UPDATE SKIP LOCKED");
	}
	@Test
	void 마지막성공지문은_오래돼도_삭제하지않는다() {
		repository.pruneItems(1000);
		assertThat(sql()).contains("i.status<>'COMPLETED' OR EXISTS", "newer.status='COMPLETED'",
				"newer.source_key=i.source_key", "(newer.finished_at,newer.id)>(i.finished_at,i.id)",
				"NOT r.details_retained");
	}
	@Test
	void 연결이_남은_실행과_마지막_승인해제_이력은_보존한다() {
		repository.pruneRuns(1000);
		assertThat(sql()).contains("NOT EXISTS (SELECT 1 FROM guard_review_items",
				"NOT EXISTS (SELECT 1 FROM guard_review_findings");
		clearInvocations(jdbc);
		repository.pruneHistory(1000);
		assertThat(sql()).contains("newer.candidate_id=h.candidate_id AND newer.action=h.action AND newer.id>h.id");
	}
	@Test
	void 발견사례는_분석당시_자료만_보여주고_만료후_변경된원문으로_대체하지않는다() {
		repository.findings(2);
		assertThat(sql())
				.contains("i.question_snapshot", "i.answer_snapshot", "detailsRetained",
						"i.source_fingerprint=f.source_fingerprint", "i.guard_stage AS \"guardStage\"")
				.doesNotContain("question_log", "answer_attempts_history");
	}

	@Test
	void 검사생략의_검사시각은_비우고_이미_기록한_거절은_덮어쓰지않는다() {
		repository.recordCaptureResult(3, "원문", "답변", "SKIPPED", "[]", "DISABLED");
		assertThat(sql()).contains("guard_stage=?", "CASE WHEN ?='SKIPPED' THEN NULL", "guard_outcome='PENDING'");
		verify(jdbc).update(anyString(), eq("원문"), eq("답변"), eq("SKIPPED"), eq("[]"),
				eq("DISABLED"), eq("SKIPPED"), eq("원문"), eq(3L));
	}
	@Test
	void 진행중_분석은_소유권이_없는_재검사에만_양보한다() {
		when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
		assertThat(repository.hasRunnableValidation()).isTrue();
		verify(jdbc).queryForObject(
				argThat(sql -> sql.contains("kind='VALIDATION'") && sql.contains("status IN ('QUEUED','RUNNING')")
						&& sql.contains("lease_until IS NULL OR lease_until < LOCALTIMESTAMP")),
				eq(Boolean.class));
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
	void 준비_실패도_기존_재시도_대기시간과_사용권_반납을_재사용한다(int failures) {
		UUID token = UUID.randomUUID();
		when(jdbc.queryForObject(contains("consecutiveFailures"), eq(Integer.class), eq(9L))).thenReturn(failures);
		repository.retryAnalysis(9, token, new Source(1, "one", "질문", null), "LLM-004");
		int delay = failures == 1 ? 10 : 30;
		verify(jdbc).update(contains("make_interval(secs=>?)"), eq(delay), eq(delay), eq(9L), eq(9L), eq(token));
		assertThat(mockingDetails(jdbc).getInvocations().stream().map(i -> i.getArgument(0, String.class)))
				.noneMatch(sql -> sql.contains("UPDATE guard_review_items"));
	}

	@Test
	void 최초_시도_포함_세번_실패하면_재시도를_예약하지_않는다() {
		UUID token = UUID.randomUUID();
		when(jdbc.queryForObject(contains("consecutiveFailures"), eq(Integer.class), eq(9L))).thenReturn(3);
		repository.retryAnalysis(9, token, new Source(1, "one", "질문", null), "LLM-004");
		verify(jdbc).update(contains("UPDATE guard_review_items"), eq("LLM-004"), eq(1L), eq(9L));
		assertThat(mockingDetails(jdbc).getInvocations().stream().map(i -> i.getArgument(0, String.class)))
				.anyMatch(sql -> sql.contains("REVIEW_FAILED"))
				.noneMatch(sql -> sql.contains("make_interval(secs=>?)"));
	}
}
