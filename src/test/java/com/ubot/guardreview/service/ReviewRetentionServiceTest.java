package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ubot.guardreview.model.ReviewModels.RETENTION;

import com.ubot.guardreview.repository.ReviewRepository;
import org.junit.jupiter.api.Test;

class ReviewRetentionServiceTest {
	ReviewRepository repository = mock(ReviewRepository.class);
	ReviewRetentionService retention = new ReviewRetentionService(repository);
	@Test
	void 보관기준은_상세30일_요약90일_이력1년이다() {
		assertThat(RETENTION.detailDays()).isEqualTo(30);
		assertThat(RETENTION.summaryDays()).isEqualTo(90);
		assertThat(RETENTION.historyDays()).isEqualTo(365);
	}
	@Test
	void 승인과_분석완료를_직렬화하고_상세부터_단계별로_정리한다() {
		retention.cleanup();
		var order = inOrder(repository);
		order.verify(repository).catalogLock();
		order.verify(repository).analysisLock();
		order.verify(repository).expireRunDetails(1000);
		order.verify(repository).clearSourceSnapshots(1000);
		order.verify(repository).pruneItems(1000);
		order.verify(repository).pruneRuns(1000);
		order.verify(repository).pruneHistory(1000);
		verifyNoMoreInteractions(repository);
	}
	@Test
	void 정리물량이_많아도_한번에_무한정_반복하지않는다() {
		when(repository.pruneItems(1000)).thenReturn(1000);
		retention.cleanup();
		verify(repository, times(ReviewRetentionService.MAX_BATCHES)).pruneItems(1000);
		verify(repository).pruneRuns(1000);
	}
	@Test
	void 단계가_실패하면_뒤의_삭제를_실행하지않는다() {
		when(repository.clearSourceSnapshots(1000)).thenThrow(new IllegalStateException("fake failure"));
		assertThatThrownBy(retention::cleanup).isInstanceOf(IllegalStateException.class);
		verify(repository, never()).pruneItems(anyInt());
		verify(repository, never()).pruneRuns(anyInt());
	}
}
