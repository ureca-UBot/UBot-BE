package com.ubot.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.billing.entity.BillingRecord;
import com.ubot.notification.enums.NotificationType;
import com.ubot.notification.repository.NotificationRepository;
import com.ubot.reservation.entity.StoreReservation;
import com.ubot.reservation.enums.ReservationPurpose;
import com.ubot.store.entity.Store;
import com.ubot.subscription.entity.Subscription;
import com.ubot.user.entity.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("알림 서비스 단위 테스트")
class NotificationServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(notificationRepository);

    @Test
    @DisplayName("읽지 않은 알림 개수를 반환한다")
    void returnsUnreadCount() {
        when(notificationRepository.countByUserIdAndReadAtIsNull(1L)).thenReturn(3L);

        assertThat(service.getUnreadCount(1L).unreadCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("전체 읽음 처리는 새로 읽음 처리한 개수를 반환한다")
    void readsAllNotifications() {
        when(notificationRepository.markAllRead(eq(1L), any(LocalDateTime.class))).thenReturn(4);

        assertThat(service.readAllNotifications(1L).updatedCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("방문 예약 리마인더는 방문이 내일이면 내일로 안내하고 예약별 dedupe_key로 저장한다")
    void createsReservationReminderForTomorrow() {
        StoreReservation reservation = reservation(LocalDateTime.of(2026, 10, 16, 10, 0));
        when(notificationRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        boolean created = service.createReservationReminder(reservation, TODAY);

        assertThat(created).isTrue();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertIfAbsent(
                eq(1L),
                eq("RESERVATION_REMINDER"),
                eq("방문 예약 리마인더"),
                message.capture(),
                eq(100L),
                eq("RESERVATION_REMINDER:100"),
                any(LocalDateTime.class));
        assertThat(message.getValue()).isEqualTo("강남점 10월 16일 (금) 10:00 요금제 변경 방문이 내일입니다.");
    }

    @Test
    @DisplayName("방문 예약 리마인더는 방문이 오늘이면 오늘로 안내한다")
    void createsReservationReminderForToday() {
        StoreReservation reservation = reservation(LocalDateTime.of(2026, 10, 15, 14, 0));
        when(notificationRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        service.createReservationReminder(reservation, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertIfAbsent(
                any(), any(), any(), message.capture(), any(), any(), any());
        assertThat(message.getValue()).isEqualTo("강남점 10월 15일 (목) 14:00 요금제 변경 방문이 오늘입니다.");
    }

    @Test
    @DisplayName("이미 같은 예약의 리마인더가 있으면 false를 반환한다")
    void returnsFalseWhenReservationReminderAlreadyExists() {
        StoreReservation reservation = reservation(LocalDateTime.of(2026, 10, 16, 10, 0));
        when(notificationRepository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThat(service.createReservationReminder(reservation, TODAY)).isFalse();
    }

    @Test
    @DisplayName("납부 기한 임박 알림은 남은 일수와 낼 금액을 안내하고 청구별 dedupe_key로 저장한다")
    void createsDueSoonReminderWithDaysLeft() {
        BillingRecord billing = billing(LocalDate.of(2026, 10, 18));
        when(notificationRepository.insertWithoutReservationIfAbsent(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        boolean created = service.createBillingReminder(billing, NotificationType.BILLING_DUE_SOON, TODAY);

        assertThat(created).isTrue();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                eq(5L),
                eq("BILLING_DUE_SOON"),
                eq("납부 기한 안내"),
                message.capture(),
                eq("BILLING_DUE_SOON:7"),
                any(LocalDateTime.class));
        assertThat(message.getValue())
                .isEqualTo("10월 요금 30,000원의 납부 기한이 3일 남았습니다. (기한: 10월 18일)");
    }

    @Test
    @DisplayName("납부 기한이 오늘이면 오늘이라고 안내한다")
    void createsDueSoonReminderOnDueDate() {
        BillingRecord billing = billing(TODAY);
        when(notificationRepository.insertWithoutReservationIfAbsent(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.createBillingReminder(billing, NotificationType.BILLING_DUE_SOON, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                any(), any(), any(), message.capture(), any(), any());
        assertThat(message.getValue()).isEqualTo("10월 요금 30,000원의 납부 기한이 오늘입니다.");
    }

    @Test
    @DisplayName("연체 알림은 납부 기한이 지났음을 안내하고 청구별 dedupe_key로 저장한다")
    void createsOverdueReminder() {
        BillingRecord billing = billing(LocalDate.of(2026, 10, 10));
        when(notificationRepository.insertWithoutReservationIfAbsent(any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.createBillingReminder(billing, NotificationType.BILLING_OVERDUE, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).insertWithoutReservationIfAbsent(
                eq(5L),
                eq("BILLING_OVERDUE"),
                eq("요금 연체 안내"),
                message.capture(),
                eq("BILLING_OVERDUE:7"),
                any(LocalDateTime.class));
        assertThat(message.getValue())
                .isEqualTo("10월 요금 30,000원이 납부 기한(10월 10일)을 지났습니다. 빠른 납부를 부탁드립니다.");
    }

    @Test
    @DisplayName("이미 같은 청구의 알림이 있으면 false를 반환한다")
    void returnsFalseWhenBillingReminderAlreadyExists() {
        BillingRecord billing = billing(LocalDate.of(2026, 10, 18));
        when(notificationRepository.insertWithoutReservationIfAbsent(any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThat(service.createBillingReminder(billing, NotificationType.BILLING_DUE_SOON, TODAY)).isFalse();
    }

    @Test
    @DisplayName("요금 리마인더가 아닌 유형으로는 만들 수 없다")
    void rejectsNonBillingType() {
        BillingRecord billing = billing(LocalDate.of(2026, 10, 18));

        assertThatThrownBy(() -> service.createBillingReminder(billing, NotificationType.RESERVATION_REMINDER, TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        verify(notificationRepository, never())
                .insertWithoutReservationIfAbsent(any(), any(), any(), any(), any(), any());
    }

    private StoreReservation reservation(LocalDateTime visitAt) {
        Store store = mock(Store.class);
        when(store.getStoreName()).thenReturn("강남점");
        StoreReservation reservation = mock(StoreReservation.class);
        when(reservation.getReservationId()).thenReturn(100L);
        when(reservation.getUserId()).thenReturn(1L);
        when(reservation.getStore()).thenReturn(store);
        when(reservation.getPurpose()).thenReturn(ReservationPurpose.PLAN_CHANGE);
        when(reservation.getVisitAt()).thenReturn(visitAt);
        return reservation;
    }

    // 총 청구액 35,000원 중 5,000원을 냈으므로 낼 금액은 30,000원이다.
    private BillingRecord billing(LocalDate dueDate) {
        return BillingRecord.builder()
                .billingId(7L)
                .subscription(Subscription.builder().user(User.builder().id(5L).build()).build())
                .billingMonth(LocalDate.of(2026, 10, 1))
                .totalAmount(35_000)
                .paidAmount(5_000)
                .dueDate(dueDate)
                .build();
    }
}