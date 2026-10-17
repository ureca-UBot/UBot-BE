package com.ubot.notification.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.notification.enums.DataUsageStage;
import com.ubot.notification.service.NotificationService;
import com.ubot.usage.repository.DataUsageTarget;
import com.ubot.usage.repository.UsageRecordRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("데이터 사용량 리마인더 서비스 테스트")
class DataUsageReminderServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private final UsageRecordRepository usageRecordRepository = mock(UsageRecordRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final DataUsageReminderService service = new DataUsageReminderService(usageRecordRepository,
            notificationService);

    @Test
    @DisplayName("이번 달 사용 기록 중 80% 이상 사용한 기록을 조회한다")
    void searchesCurrentMonthFromLowestPercent() {
        when(usageRecordRepository.findDataUsageTargets(any(), anyInt())).thenReturn(List.of());

        service.sendReminders(TODAY);

        verify(usageRecordRepository).findDataUsageTargets(LocalDate.of(2026, 10, 1), 80);
    }

    @Test
    @DisplayName("80% 이상 100% 미만이면 80% 단계, 100% 이상이면 100% 단계 알림을 만든다")
    void createsReminderByReachedStage() {
        DataUsageTarget eighty = target(1L, 8_600, 10_000);
        DataUsageTarget hundred = target(2L, 10_500, 10_000);
        returnTargets(eighty, hundred);
        when(notificationService.createDataUsageReminder(any(), any(), eq(TODAY))).thenReturn(true);

        assertThat(service.sendReminders(TODAY)).isEqualTo(2);
        verify(notificationService).createDataUsageReminder(eighty, DataUsageStage.P80, TODAY);
        verify(notificationService).createDataUsageReminder(hundred, DataUsageStage.P100, TODAY);
    }

    @Test
    @DisplayName("80%에 못 미치는 기록은 알림을 만들지 않는다")
    void skipsRecordBelowLowestStage() {
        returnTargets(target(1L, 7_000, 10_000));

        assertThat(service.sendReminders(TODAY)).isZero();
        verify(notificationService, never()).createDataUsageReminder(any(), any(), any());
    }

    @Test
    @DisplayName("이미 같은 단계 알림이 있는 기록은 새로 만든 개수에 포함하지 않는다")
    void doesNotCountExistingReminder() {
        returnTargets(target(1L, 8_600, 10_000));
        when(notificationService.createDataUsageReminder(any(), any(), any())).thenReturn(false);

        assertThat(service.sendReminders(TODAY)).isZero();
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지 기록은 계속 처리한다")
    void continuesAfterFailure() {
        DataUsageTarget failing = target(1L, 8_600, 10_000);
        DataUsageTarget succeeding = target(2L, 9_000, 10_000);
        returnTargets(failing, succeeding);
        doThrow(new IllegalStateException("db error"))
                .when(notificationService).createDataUsageReminder(eq(failing), any(), any());
        when(notificationService.createDataUsageReminder(eq(succeeding), any(), any())).thenReturn(true);

        assertThat(service.sendReminders(TODAY)).isEqualTo(1);
    }

    private void returnTargets(DataUsageTarget... targets) {
        when(usageRecordRepository.findDataUsageTargets(any(), anyInt())).thenReturn(List.of(targets));
    }

    private DataUsageTarget target(Long usageId, long usedMb, long allowanceMb) {
        DataUsageTarget target = mock(DataUsageTarget.class);
        when(target.getUsageId()).thenReturn(usageId);
        when(target.getDataUsedMb()).thenReturn(usedMb);
        when(target.getAllowanceMb()).thenReturn(allowanceMb);
        return target;
    }
}