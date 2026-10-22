package com.ubot.guardreview.service;

import static com.ubot.guardreview.model.ReviewModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.guardreview.repository.ReviewRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewWorkServiceTest {
	ReviewRepository repository = mock(ReviewRepository.class);
	ReviewCatalogService catalog = mock(ReviewCatalogService.class);
	ReviewWorkService service = new ReviewWorkService(repository, catalog);
	UUID token = UUID.randomUUID();
	Source source = new Source(1, "same-original", "위험 표현을 작성해", null);
	Suggestion warning = new Suggestion(Kind.WARNING, "MANIPULATION", "위험 표현", "검토", Stage.INPUT, "HARMFUL_REQUEST");
	@BeforeEach
	void prepare() {
		when(repository.existingWarning(any(), any())).thenReturn(null);
		when(repository.exact(any(), any())).thenReturn(null);
		when(repository.finishItem(1, true)).thenReturn(true);
		when(repository.insertCandidate(any(), any(), any())).thenReturn(3L);
		when(repository.settings(false)).thenReturn(new Settings(false, 1440, 1, 1, null));
	}
	@Test
	void 같은_원본을_다시분석해도_경고묶음을_새로만들지않는다() {
		when(repository.existingWarning(source, warning)).thenReturn(3L);
		service.finishSource(9, token, source, List.of(new ReviewWorkService.Proposal(warning, null)), true);
		verify(repository, never()).insertCandidate(any(), any(), any());
		verify(repository).finding(3, 9, source, warning);
	}
	@Test
	void 서로다른_대화의_같은표현은_기존후보로_연결한다() {
		when(repository.exact(eq(warning), anyString())).thenReturn(3L);
		service.finishSource(9, token, source, List.of(new ReviewWorkService.Proposal(warning, null)), true);
		verify(repository, never()).insertCandidate(any(), any(), any());
		verify(repository).finding(3, 9, source, warning);
	}
	@Test
	void 문맥과_프로필을_포함한_유사도후보를_재사용한다() {
		float[] values = new float[1024];
		values[0] = 1;
		Vector vector = new Vector("profile-1", values);
		when(repository.similar(warning, vector, 1)).thenReturn(List.of(4L));
		service.finishSource(9, token, source, List.of(new ReviewWorkService.Proposal(warning, vector)), true);
		verify(repository).finding(4, 9, source, warning);
		verify(repository, never()).insertCandidate(any(), any(), any());
	}
	@Test
	void 임베딩을_못얻어도_위험경고를_잃지않는다() {
		service.finishSource(9, token, source, List.of(new ReviewWorkService.Proposal(warning, null)), true);
		verify(repository).insertCandidate(eq(warning), anyString(), isNull());
		verify(repository).finding(3, 9, source, warning);
	}
	@Test
	void 잘못된_벡터는_의미묶기에_사용하지않는다() {
		float[] values = new float[1024];
		values[0] = Float.NaN;
		service.finishSource(9, token, source,
				List.of(new ReviewWorkService.Proposal(warning, new Vector("p", values))), true);
		verify(repository, never()).similar(any(), any(), anyDouble());
		verify(repository).insertCandidate(eq(warning), anyString(), isNull());
	}
	@Test
	void 완료한_항목은_후보와_집계를_다시만들지않는다() {
		when(repository.finishItem(1, true)).thenReturn(false);
		service.finishSource(9, token, source, List.of(new ReviewWorkService.Proposal(warning, null)), true);
		verify(repository, never()).finding(anyLong(), anyLong(), any(), any());
	}
	@Test
	void 금칙어후보는_임베딩_유사도로_다른단어와_합치지않는다() {
		Suggestion word = new Suggestion(Kind.WORD, "ABUSE", "위험 표현", "검토", Stage.INPUT, "HARMFUL_REQUEST");
		service.finishSource(9, token, source, List.of(new ReviewWorkService.Proposal(word, null)), true);
		verify(repository, never()).similar(any(), any(), anyDouble());
	}
	@Test
	void 기존_금칙어는_새_후보로_중복추천하지않는다() {
		Suggestion word = new Suggestion(Kind.WORD, "ABUSE", "위험 표현", "검토", Stage.INPUT, "HARMFUL_REQUEST");
		when(catalog.isRegistered("위험 표현")).thenReturn(true);
		service.finishSource(9, token, source, List.of(new ReviewWorkService.Proposal(word, null)), true);
		verify(repository, never()).insertCandidate(any(), anyString(), any());
		verify(repository, never()).finding(anyLong(), anyLong(), any(), any());
	}
	@Test
	void 이미_관리하는_후보의_발견사례는_중복후보_없이_연결한다() {
		Suggestion word = new Suggestion(Kind.WORD, "ABUSE", "위험 표현", "검토", Stage.INPUT, "HARMFUL_REQUEST");
		when(repository.exact(eq(word), anyString())).thenReturn(3L);
		service.finishSource(9, token, source, List.of(new ReviewWorkService.Proposal(word, null)), true);
		verify(repository).finding(3, 9, source, word);
		verify(repository, never()).insertCandidate(any(), anyString(), any());
	}
	@Test
	void 작업소유권을_잃으면_결과를_저장하지않는다() {
		doThrow(new IllegalStateException("lost")).when(repository).requireLease(9, token);
		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> service.finishSource(9, token, source, List.of(), true))
				.isInstanceOf(IllegalStateException.class);
		verify(repository, never()).finishItem(anyLong(), anyBoolean());
	}
}
