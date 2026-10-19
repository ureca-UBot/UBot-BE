package com.ubot.notification.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.billing.entity.BillingRecord;
import com.ubot.billing.repository.BillingRecordRepository;
import com.ubot.notification.enums.NotificationType;
import com.ubot.notification.service.NotificationService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("요금 납부 리마인더 서비스 테스트")
class BillingReminderServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private final BillingRecordRepository billingRecordRepository = mock(BillingRecordRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final BillingReminderService service = new BillingReminderService(billingRecordRepository,
            notificationService);

    @Test
    @DisplayName("납부 기한 임박은 오늘부터 3일 뒤까지 납부 기한인 청구를 조회한다")
    void searchesDueSoonFromTodayThroughThreeDaysLater() {
        when(billingRecordRepository.findUnpaidByDueDateBetween(any(), any())).thenReturn(List.of());

        service.sendDueSoonReminders(TODAY);

        verify(billingRecordRepository).findUnpaidByDueDateBetween(TODAY, LocalDate.of(2026, 10, 18));
    }

    @Test
    @DisplayName("연체는 30일 전부터 어제까지 납부 기한이 지난 청구를 조회한다")
    void searchesOverdueFromThirtyDaysAgoThroughYesterday() {
        when(billingRecordRepository.findUnpaidByDueDateBetween(any(), any())).thenReturn(List.of());

        service.sendOverdueReminders(TODAY);

        verify(billingRecordRepository).findUnpaidByDueDateBetween(
                LocalDate.of(2026, 9, 15), LocalDate.of(2026, 10, 14));
    }

    @Test
    @DisplayName("납부 기한 임박 알림은 새로 만든 개수만 센다")
    void countsOnlyNewDueSoonReminders() {
        BillingRecord created = mock(BillingRecord.class);
        BillingRecord existing = mock(BillingRecord.class);
        when(billingRecordRepository.findUnpaidByDueDateBetween(any(), any())).thenReturn(List.of(created, existing));
        when(notificationService.createBillingReminder(created, NotificationType.BILLING_DUE_SOON, TODAY))
                .thenReturn(true);
        when(notificationService.createBillingReminder(existing, NotificationType.BILLING_DUE_SOON, TODAY))
                .thenReturn(false);

        assertThat(service.sendDueSoonReminders(TODAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("연체 알림은 BILLING_OVERDUE 유형으로 만든다")
    void createsOverdueReminderWithOverdueType() {
        BillingRecord billing = mock(BillingRecord.class);
        when(billingRecordRepository.findUnpaidByDueDateBetween(any(), any())).thenReturn(List.of(billing));
        when(notificationService.createBillingReminder(billing, NotificationType.BILLING_OVERDUE, TODAY))
                .thenReturn(true);

        assertThat(service.sendOverdueReminders(TODAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지 청구는 계속 처리한다")
    void continuesAfterFailure() {
        BillingRecord failing = mock(BillingRecord.class);
        BillingRecord succeeding = mock(BillingRecord.class);
        when(billingRecordRepository.findUnpaidByDueDateBetween(any(), any()))
                .thenReturn(List.of(failing, succeeding));
        doThrow(new IllegalStateException("db error"))
                .when(notificationService).createBillingReminder(eq(failing), any(), any());
        when(notificationService.createBillingReminder(eq(succeeding), any(), any())).thenReturn(true);

        assertThat(service.sendDueSoonReminders(TODAY)).isEqualTo(1);
    }
}