package com.ubot.guardreview.service;

import static com.ubot.guardreview.model.ReviewModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ubot.guardreview.repository.ReviewRepository;
import com.ubot.notification.repository.NotificationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewAlertServiceTest {
	ReviewRepository repository = mock(ReviewRepository.class);
	NotificationRepository notifications = mock(NotificationRepository.class);
	ReviewAlertService service = new ReviewAlertService(repository, notifications);
	void pending(long generation, int count) {
		when(repository.admins()).thenReturn(List.of(91L, 92L));
		when(repository.pendingAlerts()).thenReturn(List.of(new AlertSummary(3, count, 12)));
		when(repository.alertGeneration(3)).thenReturn(generation);
		when(repository.candidate(3, true)).thenReturn(ReviewServiceTest.warning());
	}
	@Test
	void 관리자별_중복키와_신규_누적건수를_저장한다() {
		pending(15, 3);
		service.flush();
		verify(notifications).insertWithoutReservationIfAbsent(eq(91L), eq("GUARD_REVIEW"), anyString(),
				contains("신규 3건 · 누적 12건"), eq("GUARD_REVIEW:3:15:91"), any());
		verify(notifications).insertWithoutReservationIfAbsent(eq(92L), eq("GUARD_REVIEW"), anyString(), anyString(),
				eq("GUARD_REVIEW:3:15:92"), any());
		verify(repository).markNotified(3, 15);
	}
	@Test
	void 이미_통보한_사례만_있으면_다시_발송하지않는다() {
		pending(15, 3);
		service.flush();
		clearInvocations(notifications);
		when(repository.pendingAlerts()).thenReturn(List.of());
		service.flush();
		verifyNoInteractions(notifications);
	}
	@Test
	void 기존_묶음에_새사례가_생기면_새발송키를_쓴다() {
		pending(16, 1);
		service.flush();
		verify(notifications).insertWithoutReservationIfAbsent(eq(91L), any(), any(), contains("신규 1건"),
				eq("GUARD_REVIEW:3:16:91"), any());
	}
	@Test
	void 수신자가_없으면_발송완료로_표시하지않는다() {
		when(repository.admins()).thenReturn(List.of());
		service.flush();
		verify(repository, never()).markNotified(anyLong(), anyLong());
		verifyNoInteractions(notifications);
	}
}
