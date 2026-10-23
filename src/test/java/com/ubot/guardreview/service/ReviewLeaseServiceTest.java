package com.ubot.guardreview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ubot.guardreview.exception.ReviewLeaseLostException;
import com.ubot.guardreview.repository.ReviewRepository;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewLeaseServiceTest {
	ReviewRepository repository = mock(ReviewRepository.class);
	ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
	ScheduledFuture<?> future = mock(ScheduledFuture.class);
	ReviewLeaseService service = new ReviewLeaseService(repository, scheduler);
	ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
	UUID token = UUID.randomUUID();
	ReviewLeaseService.Lease start() {
		doReturn(future).when(scheduler).scheduleWithFixedDelay(task.capture(), eq(30L), eq(30L), eq(TimeUnit.SECONDS));
		return service.maintain(9, token);
	}
	@Test
	void 별도_갱신은_해당_토큰만_연장하고_완료시_예약을_해제한다() {
		when(repository.renewLease(9, token)).thenReturn(true);
		var lease = start();
		task.getValue().run();
		assertThatCode(lease::check).doesNotThrowAnyException();
		verify(repository).renewLease(9, token);
		lease.close();
		verify(future).cancel(false);
		task.getValue().run();
		verifyNoMoreInteractions(repository);
	}
	@Test
	void 갱신_실패시_검증계산을_중단하며_이전_토큰으로_반복갱신하지않는다() {
		var lease = start();
		task.getValue().run();
		assertThatThrownBy(lease::check).isInstanceOf(ReviewLeaseLostException.class);
		task.getValue().run();
		verify(repository).renewLease(9, token);
		lease.close();
	}
	@Test
	void DB_장애도_소유권을_확신하지않고_계산을_중단한다() {
		when(repository.renewLease(9, token)).thenThrow(new IllegalStateException("synthetic database outage"));
		var lease = start();
		task.getValue().run();
		assertThatThrownBy(lease::check).isInstanceOf(ReviewLeaseLostException.class);
		lease.close();
	}
	@Test
	void 종료는_자기_타이머만_닫고_새_작업과_기존_계산을_중단한다() {
		var lease = start();
		service.close();
		verify(scheduler).shutdownNow();
		assertThatThrownBy(lease::check).isInstanceOf(ReviewLeaseLostException.class);
		assertThatThrownBy(() -> service.maintain(10, UUID.randomUUID())).isInstanceOf(ReviewLeaseLostException.class);
		verifyNoInteractions(repository);
		lease.close();
	}
	@Test
	void 한_작업의_완료가_다른_작업의_갱신을_취소하지않는다() {
		var first = start();
		Runnable old = task.getValue();
		UUID other = UUID.randomUUID();
		var second = service.maintain(10, other);
		when(repository.renewLease(10, other)).thenReturn(true);
		first.close();
		old.run();
		task.getValue().run();
		assertThatCode(second::check).doesNotThrowAnyException();
		verify(repository).renewLease(10, other);
		verifyNoMoreInteractions(repository);
		second.close();
	}
}
