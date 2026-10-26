package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ubot.guardreview.model.ReviewModels.*;

import com.ubot.guardreview.model.ReviewCheckModels.Origin;
import com.ubot.guardreview.model.ReviewReassessment;
import com.ubot.guardreview.model.ReviewReassessment.Verdict;
import com.ubot.guardreview.exception.ReviewException;
import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.forbiddenword.service.ForbiddenWordService;
import com.ubot.forbiddenword.repository.ForbiddenWordRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReviewReassessmentTest {
	ReviewRepository repository = mock(ReviewRepository.class);
	ForbiddenWordService words = mock(ForbiddenWordService.class);
	ReviewService service = new ReviewService(repository, mock(ReviewValidationService.class), words,
			mock(ForbiddenWordRepository.class), mock(ReviewCatalogService.class));
	@Test
	void 선택분석은_선택한_ID만_기존_Worker에_접수한다() {
		when(repository.createRun(eq("ANALYSIS"), anyString(), eq(99L))).thenReturn(7L);
		when(repository.snapshotSelectedChecks(7, List.of(1L, 2L))).thenReturn(2);
		assertThat(service.startCheckAnalysis(List.of(2L, 1L), 99L)).isEqualTo(7);
		verify(repository).snapshotSelectedChecks(7, List.of(1L, 2L));
		verify(repository, never()).snapshotSources(anyLong(), any());
		verifyNoInteractions(words);
	}
	@Test
	void 선택분석도_빈목록과_중복ID와_잘못된ID를_거절한다() {
		for (List<Long> ids : List.of(List.<Long>of(), List.of(1L, 1L), List.of(-1L)))
			assertThatThrownBy(() -> service.startCheckAnalysis(ids, 99L)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(repository);
	}
	@Test
	void 수동_선택분석은_50건을_넘어도_전체를_접수한다() {
		var ids = java.util.stream.LongStream.rangeClosed(1, 61).boxed().toList();
		when(repository.createRun(eq("ANALYSIS"), anyString(), eq(99L))).thenReturn(7L);
		when(repository.snapshotSelectedChecks(7, ids)).thenReturn(61);
		assertThat(service.startCheckAnalysis(ids, 99L)).isEqualTo(7);
		verify(repository).snapshotSelectedChecks(7, ids);
	}
	@Test
	void 선택분석에_만료나_생략기록이_섞이면_접수를_롤백한다() {
		when(repository.createRun(eq("ANALYSIS"), anyString(), eq(99L))).thenReturn(7L);
		when(repository.snapshotSelectedChecks(7, List.of(1L, 2L))).thenReturn(1);
		assertThatThrownBy(() -> service.startCheckAnalysis(List.of(1L, 2L), 99L)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(words);
	}
	@ParameterizedTest
	@EnumSource(value = Origin.class, names = {"INPUT_LLM", "OUTPUT_LLM"})
	void 선택한_출처와_보류ID만_동결하고_기존전체분석을_접수하지않는다(Origin origin) {
		when(repository.createRun(eq("ANALYSIS"), anyString(), eq(99L))).thenReturn(7L);
		when(repository.snapshotReviewSources(7, origin, List.of(1L, 2L))).thenReturn(2);
		assertThat(service.startReassessment(origin, List.of(2L, 1L), 99L)).isEqualTo(7);
		verify(repository).createRun(eq("ANALYSIS"), argThat(json -> json.contains("REVIEW")
				&& json.contains(origin.name()) && json.contains("checkIds")), eq(99L));
		verify(repository).snapshotReviewSources(7, origin, List.of(1L, 2L));
		verify(repository, never()).snapshotSources(anyLong(), any());
		verifyNoInteractions(words);
	}
	@ParameterizedTest
	@EnumSource(value = Origin.class, names = {"INPUT_CODE", "OUTPUT_CODE", "UNKNOWN"})
	void LLM이_아닌_출처는_접수하지않는다(Origin origin) {
		assertThatThrownBy(() -> service.startReassessment(origin, List.of(1L), 99L)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(repository);
	}
	@Test
	void 잘못된_선택과_중복ID는_접수하지않는다() {
		for (List<Long> ids : List.of(List.<Long>of(), List.of(1L, 1L), List.of(0L),
				java.util.stream.LongStream.rangeClosed(1, 51).boxed().toList()))
			assertThatThrownBy(() -> service.startReassessment(Origin.INPUT_LLM, ids, 99L)).isInstanceOf(ReviewException.class);
		verifyNoInteractions(repository);
	}
	@Test
	void 만료_다른출처_다른판정이_섞여_스냅샷수가_다르면_접수를_롤백한다() {
		when(repository.createRun(eq("ANALYSIS"), anyString(), eq(99L))).thenReturn(7L);
		when(repository.snapshotReviewSources(7, Origin.INPUT_LLM, List.of(1L, 2L))).thenReturn(1);
		assertThatThrownBy(() -> service.startReassessment(Origin.INPUT_LLM, List.of(1L, 2L), 99L))
				.isInstanceOf(ReviewException.class);
		verifyNoInteractions(words);
	}
	@Test
	void 기존분석이_진행중이면_모델작업을_중복접수하지않는다() {
		when(repository.analysisExists()).thenReturn(true);
		assertThatThrownBy(() -> service.startReassessment(Origin.INPUT_LLM, List.of(1L), 99L)).isInstanceOf(ReviewException.class);
		verify(repository, never()).createRun(anyString(), anyString(), any());
	}
	@Test
	void 정상가능성이나_자료부족을_위험표현추천과_모순되게_저장하지않는다() {
		var source = new Source(1, "check:1", "질문", null, "INPUT_LLM", "REVIEW", "{}");
		var suggestion = new Suggestion(Kind.WARNING, "FRAUD", "질문", "의심", Stage.INPUT, "HARMFUL_REQUEST");
		for (Verdict verdict : List.of(Verdict.NORMAL_POSSIBLE, Verdict.INSUFFICIENT_CONTEXT)) {
			assertThatCode(() -> new ReviewReassessment(verdict, "근거", List.of()).validate(source)).doesNotThrowAnyException();
			assertThatThrownBy(() -> new ReviewReassessment(verdict, "근거", List.of(suggestion)).validate(source))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> new ReviewReassessment(Verdict.ISSUE_SUSPECTED, "의심", List.of()).validate(source))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
