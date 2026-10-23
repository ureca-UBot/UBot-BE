package com.ubot.guardreview.service;

import static com.ubot.guardreview.model.ReviewModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.forbiddenword.service.ForbiddenWordCatalogLock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 외부 DB 없이 실제 저장소가 넘기는 쿼리의 범위·중복키·프로필 조건을 검증합니다. */
class ReviewRepositoryTest {
	JdbcTemplate jdbc = mock(JdbcTemplate.class);
	ReviewRepository repository = new ReviewRepository(jdbc, mock(ForbiddenWordCatalogLock.class));
	String sql() {
		return mockingDetails(jdbc).getInvocations().iterator().next().getArgument(0, String.class);
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
	void 재분석_경고_중복은_벡터가_아닌_원본과_유형으로_확인한다() {
		repository.existingWarning(new Source(1, "original", "질문", null),
				new Suggestion(Kind.WARNING, "FRAUD", "질문", "검토", Stage.INPUT, "HARMFUL_REQUEST"));
		assertThat(sql()).contains("f.source_key=?", "f.stage=?", "f.category=?", "c.kind='WARNING'")
				.doesNotContain("<=>");
	}
	@Test
	void 동시_작업은_건너뛰고_만료된_lease만_재접수한다() {
		repository.claim(UUID.randomUUID());
		assertThat(sql()).contains("FOR UPDATE SKIP LOCKED", "lease_until < LOCALTIMESTAMP", "lease_token=?",
				"CASE WHEN kind='VALIDATION' THEN 0 ELSE 1 END,id");
	}
	@Test
	void 스냅샷은_재시도와_하위질문을_증식시키지않는다() {
		repository.snapshotSources(7, AnalysisMode.FULL);
		assertThat(sql())
				.contains("PARTITION BY a.conversation_id,a.user_id,a.idempotency_key", "source_rank=1",
						"a.created_at <=", "a.attempt_count DESC", "ON CONFLICT(run_id,source_key) DO NOTHING")
				.doesNotContain("source_attempt_id IS NULL");
	}
	@Test
	void 의도_재검색도_포함하고_하위질문_중복답변은_한번만_읽는다() {
		repository.snapshotSources(7, AnalysisMode.INCREMENTAL);
		assertThat(sql()).contains("attempt_id=a.attempt_id", "ORDER BY sub_question_index,id LIMIT 1", "created_at <=")
				.doesNotContain("source_attempt_id IS NULL");
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
		assertThat(sql()).contains("jsonb_build_array(s.question,s.answer)", "sha256", "previous.status='COMPLETED'",
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
						"i.source_fingerprint=f.source_fingerprint")
				.doesNotContain("question_log", "answer_attempts_history");
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
}
