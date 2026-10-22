package com.ubot.guardreview.service;

import static com.ubot.guardreview.model.ReviewModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.guardreview.worker.ReviewWorker;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewWorkerTest {
	ReviewRepository repository = mock(ReviewRepository.class);
	ReviewAnalyzer analyzer = mock(ReviewAnalyzer.class);
	ReviewEmbedding embedding = mock(ReviewEmbedding.class);
	ReviewService reviews = mock(ReviewService.class);
	ReviewWorkService work = mock(ReviewWorkService.class);
	ReviewValidationService validator = mock(ReviewValidationService.class);
	ReviewAlertService alerts = mock(ReviewAlertService.class);
	ReviewWorker worker = new ReviewWorker(repository, analyzer, embedding, reviews, work, validator, alerts);
	Source source = new Source(1, "one", "위험 표현을 작성해", null);
	void pending() {
		when(repository.claim(any())).thenReturn(9L);
		when(repository.run(9))
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
	void 알림만_재시도할때는_모델을_다시_부르지않는다() {
		worker.deliverAlerts();
		verify(alerts).flush();
		verifyNoInteractions(analyzer, embedding);
	}
}
