package com.ubot.guardreview.service;

import static com.ubot.guardreview.model.ReviewModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.guardreview.exception.ReviewAnalysisDeferredException;
import com.ubot.guardreview.worker.ReviewWorker;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class ReviewWorkerTest {
	ReviewRepository repository = mock(ReviewRepository.class);
	ReviewAnalyzer analyzer = mock(ReviewAnalyzer.class);
	ReviewModelRuntime models = mock(ReviewModelRuntime.class);
	ReviewModelRuntime.Session model = mock(ReviewModelRuntime.Session.class);
	ReviewEmbedding embedding = mock(ReviewEmbedding.class);
	ReviewService reviews = mock(ReviewService.class);
	ReviewWorkService work = mock(ReviewWorkService.class);
	ReviewValidationService validator = mock(ReviewValidationService.class);
	ReviewAlertService alerts = mock(ReviewAlertService.class);
	ReviewLeaseService leases = mock(ReviewLeaseService.class);
	ReviewLeaseService.Lease lease = mock(ReviewLeaseService.Lease.class);
	@BeforeEach
	void lease() {
		when(leases.maintain(anyLong(), any())).thenReturn(lease);
		when(models.acquire(anyLong(), any())).thenReturn(model);
	}
	ReviewWorker worker = new ReviewWorker(repository, analyzer, models, embedding, reviews, work, validator, alerts,
			leases);
	Source source = new Source(1, "one", "위험 표현을 작성해", null);
	void pending() {
		when(repository.claim(any())).thenReturn(9L);
		when(repository.runSummary(9))
				.thenReturn(new Run(9, "ANALYSIS", "RUNNING", 0, 1, LocalDateTime.now(), "{}", LocalDateTime.now()));
		when(repository.nextSource(9)).thenReturn(source, null);
	}
	@Test
	void 접수작업이_없으면_모델과_임베딩을_호출하지않는다() {
		when(repository.claim(any())).thenReturn(null);
		worker.poll();
		verifyNoInteractions(analyzer, embedding, work);
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"INPUT_LLM", "OUTPUT_LLM"})
	void 입력출력_보류는_기존모델수명관리_안에서_한번만_재검토하고_정상가능성도_저장한다(String origin) {
		source = new Source(1, "guard-check:1", "질문", "답변", origin, "REVIEW", "{}");
		pending();
		var result = new com.ubot.guardreview.model.ReviewReassessment(
				com.ubot.guardreview.model.ReviewReassessment.Verdict.NORMAL_POSSIBLE, "정상일 가능성이 있습니다.", List.of());
		when(analyzer.reassess(source)).thenReturn(result);
		worker.poll();
		verify(analyzer, times(1)).reassess(source);
		verify(analyzer, never()).analyze(any());
		verify(work).finishReviewedSource(eq(9L), any(), eq(source), eq(List.of()), eq(result));
		verify(model).close();
		verifyNoInteractions(embedding, reviews);
	}
	@Test
	void 기본_가드전답변은_보류결과여도_원문분석을_하며_원래_판정을_재판정하지않는다() {
		source = new Source(1, "raw", "질문", "생성 답변", "OUTPUT_RAW", "REVIEW", "{}");
		pending();
		when(analyzer.analyze(source)).thenReturn(List.of());
		worker.poll();
		verify(analyzer).analyze(source);
		verify(analyzer, never()).reassess(any());
		verify(work).finishSource(eq(9L), any(), eq(source), eq(List.of()), eq(true));
	}
	@Test
	void 보류의_잘못된_재검토응답은_허용이나_정상으로_저장하지않는다() {
		source = new Source(1, "guard-check:1", "질문", null, "INPUT_LLM", "REVIEW", "{}");
		pending();
		when(analyzer.reassess(source)).thenReturn(null);
		worker.poll();
		verify(work).finishSource(eq(9L), any(), eq(source), eq(List.of()), eq(false));
		verify(work, never()).finishReviewedSource(anyLong(), any(), any(), anyList(), any());
		verifyNoInteractions(embedding, reviews);
	}
	@Test
	void 위험문장은_검토결과로만_전달한다() {
		pending();
		var s = new Suggestion(Kind.WARNING, "MANIPULATION", "위험 표현", "검토", Stage.INPUT, "HARMFUL_REQUEST");
		when(analyzer.analyze(source)).thenReturn(List.of(s));
		worker.poll();
		verify(work).finishSource(eq(9L), any(), eq(source),
				argThat(list -> list.size() == 1 && list.getFirst().suggestion().kind() == Kind.WARNING), eq(true));
		verifyNoInteractions(reviews);
	}
	@Test
	void 임베딩실패는_경고등록을_실패시키지않는다() {
		pending();
		when(analyzer.analyze(source)).thenReturn(
				List.of(new Suggestion(Kind.WARNING, "MANIPULATION", "위험 표현", "검토", Stage.INPUT, "HARMFUL_REQUEST")));
		when(embedding.embed(any())).thenThrow(new IllegalStateException("offline"));
		worker.poll();
		verify(work).finishSource(eq(9L), any(), eq(source),
				argThat(list -> list.size() == 1 && list.getFirst().vector() == null), eq(true));
	}
	@Test
	void 잘못된_AI응답은_후보로_저장하지않는다() {
		pending();
		when(analyzer.analyze(source)).thenReturn(
				List.of(new Suggestion(Kind.WORD, "ABUSE", "원본에없는표현", "검토", Stage.INPUT, "HARMFUL_REQUEST")));
		worker.poll();
		verify(work).finishSource(eq(9L), any(), eq(source), eq(List.of()), eq(false));
		verifyNoInteractions(embedding);
	}
	@Test
	void 분석실패는_실패상태로_저장해_다음에_재개한다() {
		pending();
		when(analyzer.analyze(source)).thenThrow(new IllegalStateException("fake model failure"));
		worker.poll();
		verify(work).finishSource(eq(9L), any(), eq(source), eq(List.of()), eq(false));
		verify(work).release(eq(9L), any());
	}
	@Test
	void 호출여유가_없으면_항목을_성공이나_실패로_저장하지않고_lease만_반납한다() {
		pending();
		when(analyzer.analyze(source)).thenThrow(new ReviewAnalysisDeferredException());
		worker.poll();
		verify(work, never()).finishSource(anyLong(), any(), any(), anyList(), anyBoolean());
		verify(work, never()).finishValidation(anyLong(), any(), anyString(), anyBoolean());
		verify(work).release(eq(9L), any());
		verify(analyzer).analyze(source);
		verifyNoInteractions(embedding);
	}
	@Test
	void 알림만_재시도할때는_모델을_다시_부르지않는다() {
		worker.deliverAlerts();
		verify(alerts).flush();
		verifyNoInteractions(analyzer, embedding);
	}
	@Test
	void 분석실행_자체의_실패는_검증완료로_진행률을_덮어쓰지않는다() {
		pending();
		when(repository.nextSource(9)).thenThrow(new IllegalStateException("synthetic repository failure"));
		worker.poll();
		verify(work).failAnalysis(eq(9L), any());
		verify(work, never()).finishValidation(anyLong(), any(), anyString(), anyBoolean());
		verify(lease).close();
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.EnumSource(value = com.ubot.llm.exception.LlmErrorCode.class, names = {
			"LLM_SERVICE_UNAVAILABLE", "LLM_TIMEOUT"})
	void 연결장애와_시간초과만_같은_항목을_대기후_재시도한다(com.ubot.llm.exception.LlmErrorCode error) {
		pending();
		when(analyzer.analyze(source)).thenThrow(new com.ubot.llm.exception.LlmException(error));
		worker.poll();
		verify(work).retryAnalysis(eq(9L), any(), eq(source), eq(error.getCode()));
		verify(work, never()).release(anyLong(), any());
		verify(work, never()).finishSource(anyLong(), any(), any(), anyList(), anyBoolean());
		verify(lease).close();
	}
	@Test
	void 출력거절은_일시장애_재시도에_넣지않는다() {
		pending();
		when(analyzer.analyze(source)).thenThrow(
				new com.ubot.llm.exception.LlmException(com.ubot.llm.exception.LlmErrorCode.LLM_OUTPUT_REJECTED));
		worker.poll();
		verify(work, never()).retryAnalysis(anyLong(), any(), any(), anyString());
		verify(work).finishSource(eq(9L), any(), eq(source), eq(List.of()), eq(false));
	}
	@Test
	void 계산중_소유권을_잃으면_결과와_실패를_모두_덮어쓰지않는다() {
		pending();
		doNothing().doThrow(new com.ubot.guardreview.exception.ReviewLeaseLostException()).when(lease).check();
		when(analyzer.analyze(source)).thenReturn(List.of());
		worker.poll();
		verifyNoInteractions(work);
		verify(lease).close();
	}
	@Test
	void 알림전송_장애는_이미_완료한_분석을_실패로_변경하지않는다() {
		pending();
		when(analyzer.analyze(source)).thenReturn(List.of());
		doThrow(new IllegalStateException("synthetic notification failure")).when(alerts).flush();
		worker.poll();
		verify(work).release(eq(9L), any());
		verify(work, never()).failAnalysis(anyLong(), any());
		verify(work, never()).finishValidation(anyLong(), any(), anyString(), anyBoolean());
	}

	@Test
	void 접수한_규칙버전과_Worker가_다르면_사례계산_전에_검증을_종료한다() {
		when(repository.claim(any())).thenReturn(9L);
		when(repository.runSummary(9)).thenReturn(
				new Run(9, "VALIDATION", "RUNNING", 0, 5168, LocalDateTime.now(), "{}", LocalDateTime.now()));
		when(reviews.getTestRequest(9)).thenReturn(
				new ReviewService.TestRequest(List.of(), List.of(), java.util.Set.of("동결금칙어"), "api-rules", "dataset"));
		when(validator.datasetVersion()).thenReturn("dataset");
		when(validator.baseVersion(java.util.Set.of("동결금칙어"))).thenReturn("worker-rules");
		worker.poll();
		verify(validator, never()).dataset();
		verify(validator, never()).validate(anyCollection(), anySet(), anyList(), anyString(), any(Runnable.class));
		verify(work).finishValidation(eq(9L), any(), eq("{\"error\":\"REVIEW_FAILED\"}"), eq(false));
		verify(work, never()).failAnalysis(anyLong(), any());
		verifyNoInteractions(analyzer, embedding);
		verify(lease).close();
	}

	@Test
	void 짧은_WORD는_경고로_넘기고_함께_찾은_다른_후보는_유지한다() {
		pending();
		var shortWord = new Suggestion(Kind.WORD, "ABUSE", "위험", "검토", Stage.INPUT, "HARMFUL_REQUEST");
		var word = new Suggestion(Kind.WORD, "ABUSE", "위험 표현", "검토", Stage.INPUT, "HARMFUL_REQUEST");
		when(analyzer.analyze(source)).thenReturn(List.of(shortWord, word));
		worker.poll();
		verify(embedding).embed("위험");
		verify(work).finishSource(eq(9L), any(), eq(source),
				argThat(list -> list.size() == 2 && list.get(0).suggestion().kind() == Kind.WARNING
						&& list.get(0).suggestion().expression().equals("위험") && list.get(1).suggestion().equals(word)),
				eq(true));
	}

	@Test
	void 분석_묶음은_모델을_한번_준비하고_마지막_항목후_해제한다() {
		pending();
		Source second = new Source(2, "two", "다른 질문", "답변");
		when(repository.nextSource(9)).thenReturn(source, second, null);
		when(analyzer.analyze(any())).thenReturn(List.of());
		worker.poll();
		var ordered = inOrder(models, analyzer, work, model);
		ordered.verify(models).acquire(eq(9L), any());
		ordered.verify(analyzer).analyze(source);
		ordered.verify(work).finishSource(eq(9L), any(), eq(source), anyList(), eq(true));
		ordered.verify(analyzer).analyze(second);
		ordered.verify(work).finishSource(eq(9L), any(), eq(second), anyList(), eq(true));
		ordered.verify(model).close();
		ordered.verify(work).release(eq(9L), any());
		verify(models, times(1)).acquire(anyLong(), any());
	}
	@Test
	void GPU가_바쁘면_자료를_실패로_저장하지않고_다음_주기로_미룬다() {
		pending();
		when(models.acquire(anyLong(), any())).thenThrow(new ReviewModelRuntime.Busy());
		worker.poll();
		verifyNoInteractions(analyzer, embedding);
		verify(work).release(eq(9L), any());
		verify(work, never()).finishSource(anyLong(), any(), any(), anyList(), anyBoolean());
	}
	@Test
	void 계산_중_새_오차단_검사가_접수되면_한_항목후_모델을_해제하고_양보한다() {
		pending();
		when(repository.nextSource(9)).thenReturn(source);
		when(analyzer.analyze(source)).thenReturn(List.of());
		when(repository.hasRunnableValidation()).thenReturn(true);
		worker.poll();
		verify(analyzer, times(1)).analyze(source);
		verify(repository, times(1)).nextSource(9);
		verify(model).close();
		verify(work).release(eq(9L), any());
	}
	@Test
	void 분석이_거절되거나_재시도_예약돼도_모델_사용권은_반납한다() {
		pending();
		when(analyzer.analyze(source))
				.thenThrow(new com.ubot.llm.exception.LlmException(com.ubot.llm.exception.LlmErrorCode.LLM_TIMEOUT));
		worker.poll();
		verify(model).close();
		verify(work).retryAnalysis(eq(9L), any(), eq(source), anyString());
		verify(work, never()).release(anyLong(), any());
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.EnumSource(value = com.ubot.llm.exception.LlmErrorCode.class, names = {
			"LLM_SERVICE_UNAVAILABLE", "LLM_TIMEOUT"})
	void 일시적인_모델_준비_실패는_미처리_항목을_보존하고_기존_재시도로_연결한다(com.ubot.llm.exception.LlmErrorCode error) {
		pending();
		when(models.acquire(anyLong(), any())).thenThrow(new com.ubot.llm.exception.LlmException(error));
		worker.poll();
		verify(work).retryAnalysis(eq(9L), any(), eq(source), eq(error.getCode()));
		verify(work, never()).failAnalysis(anyLong(), any());
		verify(work, never()).finishSource(anyLong(), any(), any(), anyList(), anyBoolean());
		verify(work, never()).release(anyLong(), any());
		verifyNoInteractions(analyzer, embedding, model);
		verify(lease).close();
	}

	@Test
	void 준비_재시도_이후에는_같은_항목부터_분석을_계속한다() {
		pending();
		when(repository.nextSource(9)).thenReturn(source, source, null);
		var secondLease = mock(ReviewLeaseService.Lease.class);
		when(leases.maintain(anyLong(), any())).thenReturn(lease, secondLease);
		when(models.acquire(anyLong(), any()))
				.thenThrow(new com.ubot.llm.exception.LlmException(com.ubot.llm.exception.LlmErrorCode.LLM_TIMEOUT))
				.thenReturn(model);
		when(analyzer.analyze(source)).thenReturn(List.of());
		worker.poll();
		worker.poll();
		var ordered = inOrder(work, analyzer, model);
		ordered.verify(work).retryAnalysis(eq(9L), any(), eq(source), eq("LLM-004"));
		ordered.verify(analyzer).analyze(source);
		ordered.verify(work).finishSource(eq(9L), any(), eq(source), eq(List.of()), eq(true));
		ordered.verify(model).close();
		ordered.verify(work).release(eq(9L), any());
		verify(work, never()).failAnalysis(anyLong(), any());
		verify(lease).close();
		verify(secondLease).close();
	}

	@Test
	void 준비_실패와_동시에_작업_소유권을_잃으면_재시도_상태를_덮어쓰지않는다() {
		pending();
		doNothing().doThrow(new com.ubot.guardreview.exception.ReviewLeaseLostException()).when(lease).check();
		when(models.acquire(anyLong(), any()))
				.thenThrow(new com.ubot.llm.exception.LlmException(com.ubot.llm.exception.LlmErrorCode.LLM_TIMEOUT));
		worker.poll();
		verifyNoInteractions(work, analyzer, embedding);
		verify(lease).close();
	}

	@Test
	void 준비_설정_오류는_반복하지_않고_분석_실패로_남긴다() {
		pending();
		when(models.acquire(anyLong(), any())).thenThrow(new IllegalStateException("invalid configuration"));
		worker.poll();
		verify(work).failAnalysis(eq(9L), any());
		verify(work, never()).retryAnalysis(anyLong(), any(), any(), anyString());
		verify(work, never()).finishSource(anyLong(), any(), any(), anyList(), anyBoolean());
		verifyNoInteractions(analyzer, embedding);
		verify(lease).close();
	}

	@Test
	void 준비_실패라도_출력_거절_코드는_재시도에_넣지않는다() {
		pending();
		when(models.acquire(anyLong(), any())).thenThrow(
				new com.ubot.llm.exception.LlmException(com.ubot.llm.exception.LlmErrorCode.LLM_OUTPUT_REJECTED));
		worker.poll();
		verify(work, never()).retryAnalysis(anyLong(), any(), any(), anyString());
		verify(work).failAnalysis(eq(9L), any());
		verifyNoInteractions(analyzer, embedding);
	}
}
