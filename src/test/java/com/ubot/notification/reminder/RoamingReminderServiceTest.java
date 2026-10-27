package com.ubot.notification.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.notification.enums.DateReminderStage;
import com.ubot.notification.service.NotificationService;
import com.ubot.subscription.entity.SubscriptionRoaming;
import com.ubot.subscription.enums.SubscriptionRoamingStatus;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.subscription.repository.SubscriptionRoamingRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("로밍 리마인더 서비스 테스트")
class RoamingReminderServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDateTime LONG_AGO = LocalDateTime.of(2026, 10, 1, 9, 0);

    private final SubscriptionRoamingRepository subscriptionRoamingRepository = mock(
            SubscriptionRoamingRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final RoamingReminderService service = new RoamingReminderService(subscriptionRoamingRepository,
            notificationService);

    @Test
    @DisplayName("시작 알림은 오늘부터 내일까지 시작하는 예정·진행 중 로밍을 활성 회선에서 조회한다")
    void searchesStartingTodayAndTomorrow() {
        when(subscriptionRoamingRepository.findByStartDateBetween(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.sendStartReminders(TODAY);

        verify(subscriptionRoamingRepository).findByStartDateBetween(
                TODAY, TODAY.plusDays(1),
                List.of(SubscriptionRoamingStatus.SCHEDULED, SubscriptionRoamingStatus.ACTIVE),
                SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("시작이 내일이면 D1, 오늘이면 D0 단계로 시작 알림을 만든다")
    void createsStartReminderByStage() {
        SubscriptionRoaming tomorrow = roaming(1L, TODAY.plusDays(1), TODAY.plusDays(4), LONG_AGO);
        SubscriptionRoaming today = roaming(2L, TODAY, TODAY.plusDays(3), LONG_AGO);
        returnStartTargets(tomorrow, today);
        when(notificationService.createRoamingStartReminder(any(), any(), eq(TODAY))).thenReturn(true);

        assertThat(service.sendStartReminders(TODAY)).isEqualTo(2);
        verify(notificationService).createRoamingStartReminder(tomorrow, DateReminderStage.D1, TODAY);
        verify(notificationService).createRoamingStartReminder(today, DateReminderStage.D0, TODAY);
    }

    @Test
    @DisplayName("시작 24시간 전보다 늦게 등록한 로밍은 시작 알림을 건너뛴다")
    void skipsStartReminderForRecentlyRegisteredRoaming() {
        // 시작 24시간 전은 10월 15일 0시. 10월 15일 8시에 등록했으므로 방금 등록한 로밍이다.
        SubscriptionRoaming roaming = roaming(1L, TODAY.plusDays(1), TODAY.plusDays(4),
                LocalDateTime.of(2026, 10, 15, 8, 0));
        returnStartTargets(roaming);

        assertThat(service.sendStartReminders(TODAY)).isZero();
        verify(notificationService, never()).createRoamingStartReminder(any(), any(), any());
    }

    @Test
    @DisplayName("시작 24시간 전보다 앞서(시작 전날 0시 이전) 등록한 로밍에는 시작 알림을 만든다")
    void createsStartReminderForRoamingRegisteredBeforeMinNotice() {
        SubscriptionRoaming roaming = roaming(1L, TODAY.plusDays(1), TODAY.plusDays(4),
                LocalDateTime.of(2026, 10, 14, 23, 59));
        returnStartTargets(roaming);
        when(notificationService.createRoamingStartReminder(roaming, DateReminderStage.D1, TODAY)).thenReturn(true);

        assertThat(service.sendStartReminders(TODAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 같은 단계 시작 알림이 있는 로밍은 새로 만든 개수에 포함하지 않는다")
    void doesNotCountExistingStartReminder() {
        returnStartTargets(roaming(1L, TODAY.plusDays(1), TODAY.plusDays(4), LONG_AGO));
        when(notificationService.createRoamingStartReminder(any(), any(), any())).thenReturn(false);

        assertThat(service.sendStartReminders(TODAY)).isZero();
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지 로밍은 계속 처리한다")
    void continuesStartRemindersAfterFailure() {
        SubscriptionRoaming failing = roaming(1L, TODAY.plusDays(1), TODAY.plusDays(4), LONG_AGO);
        SubscriptionRoaming succeeding = roaming(2L, TODAY.plusDays(1), TODAY.plusDays(5), LONG_AGO);
        returnStartTargets(failing, succeeding);
        doThrow(new IllegalStateException("db error"))
                .when(notificationService).createRoamingStartReminder(eq(failing), any(), any());
        when(notificationService.createRoamingStartReminder(eq(succeeding), any(), any())).thenReturn(true);

        assertThat(service.sendStartReminders(TODAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("종료 알림은 오늘부터 내일까지 종료하는 예정·진행 중 로밍을 활성 회선에서 조회한다")
    void searchesEndingTodayAndTomorrow() {
        when(subscriptionRoamingRepository.findByEndDateBetween(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.sendEndReminders(TODAY);

        verify(subscriptionRoamingRepository).findByEndDateBetween(
                TODAY, TODAY.plusDays(1),
                List.of(SubscriptionRoamingStatus.SCHEDULED, SubscriptionRoamingStatus.ACTIVE),
                SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("종료가 내일이면 D1, 오늘이면 D0 단계로 종료 알림을 만든다")
    void createsEndReminderByStage() {
        SubscriptionRoaming tomorrow = roaming(1L, TODAY.minusDays(2), TODAY.plusDays(1), LONG_AGO);
        SubscriptionRoaming today = roaming(2L, TODAY.minusDays(3), TODAY, LONG_AGO);
        returnEndTargets(tomorrow, today);
        when(notificationService.createRoamingEndReminder(any(), any(), eq(TODAY))).thenReturn(true);

        assertThat(service.sendEndReminders(TODAY)).isEqualTo(2);
        verify(notificationService).createRoamingEndReminder(tomorrow, DateReminderStage.D1, TODAY);
        verify(notificationService).createRoamingEndReminder(today, DateReminderStage.D0, TODAY);
    }

    @Test
    @DisplayName("시작일과 종료일이 같은 1일 로밍은 종료 알림을 건너뛴다")
    void skipsEndReminderForOneDayRoaming() {
        returnEndTargets(roaming(1L, TODAY, TODAY, LONG_AGO));

        assertThat(service.sendEndReminders(TODAY)).isZero();
        verify(notificationService, never()).createRoamingEndReminder(any(), any(), any());
    }

    @Test
    @DisplayName("방금 등록한 로밍이라도 종료 알림은 건너뛰지 않는다")
    void doesNotSkipEndReminderForRecentlyRegisteredRoaming() {
        SubscriptionRoaming roaming = roaming(1L, TODAY, TODAY.plusDays(1), LocalDateTime.of(2026, 10, 15, 8, 0));
        returnEndTargets(roaming);
        when(notificationService.createRoamingEndReminder(roaming, DateReminderStage.D1, TODAY)).thenReturn(true);

        assertThat(service.sendEndReminders(TODAY)).isEqualTo(1);
    }

    private void returnStartTargets(SubscriptionRoaming... roamings) {
        when(subscriptionRoamingRepository.findByStartDateBetween(any(), any(), any(), any()))
                .thenReturn(List.of(roamings));
    }

    private void returnEndTargets(SubscriptionRoaming... roamings) {
        when(subscriptionRoamingRepository.findByEndDateBetween(any(), any(), any(), any()))
                .thenReturn(List.of(roamings));
    }

    private SubscriptionRoaming roaming(Long id, LocalDate startDate, LocalDate endDate, LocalDateTime createdAt) {
        SubscriptionRoaming roaming = mock(SubscriptionRoaming.class);
        when(roaming.getSubscriptionRoamingId()).thenReturn(id);
        when(roaming.getStartDate()).thenReturn(startDate);
        when(roaming.getEndDate()).thenReturn(endDate);
        when(roaming.getCreatedAt()).thenReturn(createdAt);
        return roaming;
    }
}