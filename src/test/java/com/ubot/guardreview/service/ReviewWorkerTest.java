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
	}
	ReviewWorker worker = new ReviewWorker(repository, analyzer, embedding, reviews, work, validator, alerts, leases);
	Source source = new Source(1, "one", "위험 표현을 작성해", null);
	void pending() {
		when(repository.claim(any())).thenReturn(9L);
		when(repository.runSummary(9))
				.thenReturn(new Run(9, "ANALYSIS", "RUNNING", 0, 1, LocalDateTime.now(), "{}", LocalDateTime.now()));
		when(repository.nextSource(9)).thenReturn(source);
	}
	@Test
	void 접수작업이_없으면_모델과_임베딩을_호출하지않는다() {
		when(repository.claim(any())).thenReturn(null);
		worker.poll();
		verifyNoInteractions(analyzer, embedding, work);
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

}
