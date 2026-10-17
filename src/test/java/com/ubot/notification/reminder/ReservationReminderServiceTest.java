package com.ubot.notification.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.notification.enums.ReservationReminderStage;
import com.ubot.notification.service.NotificationService;
import com.ubot.reservation.entity.StoreReservation;
import com.ubot.reservation.enums.ReservationStatus;
import com.ubot.reservation.repository.StoreReservationRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("방문 예약 리마인더 서비스 테스트")
class ReservationReminderServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 0);

    private final StoreReservationRepository storeReservationRepository = mock(StoreReservationRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ReservationReminderService service = new ReservationReminderService(storeReservationRepository,
            notificationService);

    @Test
    @DisplayName("지금부터 24시간 안에 방문하는 RESERVED 예약을 조회한다")
    void searchesNext24Hours() {
        when(storeReservationRepository.findReminderTargets(any(), any(), any())).thenReturn(List.of());

        service.sendReminders(NOW);

        verify(storeReservationRepository).findReminderTargets(
                ReservationStatus.RESERVED, NOW, LocalDateTime.of(2026, 10, 14, 9, 0));
    }

    @Test
    @DisplayName("방문까지 24시간 이내이고 6시간을 넘게 남았으면 24시간 전 단계 알림을 만든다")
    void createsDayBeforeReminder() {
        StoreReservation reservation = reservation(1L, NOW.plusHours(20), NOW.minusDays(3));
        returnTargets(reservation);
        when(notificationService.createReservationReminder(reservation, ReservationReminderStage.D1, NOW))
                .thenReturn(true);

        assertThat(service.sendReminders(NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("방문까지 6시간 이내이면 6시간 전 단계 알림을 만든다")
    void createsSixHoursBeforeReminder() {
        StoreReservation reservation = reservation(1L, NOW.plusHours(5).plusMinutes(30), NOW.minusDays(3));
        returnTargets(reservation);
        when(notificationService.createReservationReminder(reservation, ReservationReminderStage.H6, NOW))
                .thenReturn(true);

        assertThat(service.sendReminders(NOW)).isEqualTo(1);
        verify(notificationService, never()).createReservationReminder(reservation, ReservationReminderStage.D1,
                NOW);
    }

    @Test
    @DisplayName("방문까지 1시간 이내이면 1시간 전 단계 알림을 만든다")
    void createsOneHourBeforeReminder() {
        StoreReservation reservation = reservation(1L, NOW.plusMinutes(30), NOW.minusDays(3));
        returnTargets(reservation);
        when(notificationService.createReservationReminder(reservation, ReservationReminderStage.H1, NOW))
                .thenReturn(true);

        assertThat(service.sendReminders(NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("예약을 그 단계 시점보다 늦게 잡았으면 그 단계는 건너뛴다")
    void skipsStageWhenBookedAfterIt() {
        // 방문까지 20시간 남아 24시간 전 단계인데, 예약은 방문 21시간 전(1시간 전)에 잡아 그 시점보다 늦다.
        StoreReservation reservation = reservation(1L, NOW.plusHours(20), NOW.minusHours(1));
        returnTargets(reservation);

        assertThat(service.sendReminders(NOW)).isZero();
        verify(notificationService, never()).createReservationReminder(any(), any(), any());
    }

    @Test
    @DisplayName("24시간 전 시점 뒤에 잡았어도 6시간 전 시점보다 앞서 잡았으면 6시간 전 알림을 만든다")
    void createsLaterStageWhenEarlierStageWasMissedByBooking() {
        // 방문 5시간 전, 예약은 5시간 전(방문 10시간 전)에 잡았다.
        StoreReservation reservation = reservation(1L, NOW.plusHours(5), NOW.minusHours(5));
        returnTargets(reservation);
        when(notificationService.createReservationReminder(reservation, ReservationReminderStage.H6, NOW))
                .thenReturn(true);

        assertThat(service.sendReminders(NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("방문 직전에 잡은 예약에는 어떤 단계도 만들지 않는다")
    void skipsReservationBookedJustBeforeVisit() {
        // 방문 5시간 전, 예약은 30분 전(방문 5시간 30분 전)에 잡아 6시간 전 시점보다 늦다.
        StoreReservation reservation = reservation(1L, NOW.plusHours(5), NOW.minusMinutes(30));
        returnTargets(reservation);

        assertThat(service.sendReminders(NOW)).isZero();
        verify(notificationService, never()).createReservationReminder(any(), any(), any());
    }

    @Test
    @DisplayName("이미 같은 단계 알림이 있는 예약은 새로 만든 개수에 포함하지 않는다")
    void doesNotCountExistingReminder() {
        StoreReservation reservation = reservation(1L, NOW.plusHours(20), NOW.minusDays(3));
        returnTargets(reservation);
        when(notificationService.createReservationReminder(reservation, ReservationReminderStage.D1, NOW))
                .thenReturn(false);

        assertThat(service.sendReminders(NOW)).isZero();
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지 예약은 계속 처리한다")
    void continuesAfterFailure() {
        StoreReservation failing = reservation(1L, NOW.plusHours(20), NOW.minusDays(3));
        StoreReservation succeeding = reservation(2L, NOW.plusHours(21), NOW.minusDays(3));
        returnTargets(failing, succeeding);
        doThrow(new IllegalStateException("db error"))
                .when(notificationService).createReservationReminder(eq(failing), any(), any(LocalDateTime.class));
        when(notificationService.createReservationReminder(eq(succeeding), any(), any(LocalDateTime.class)))
                .thenReturn(true);

        assertThat(service.sendReminders(NOW)).isEqualTo(1);
    }

    private void returnTargets(StoreReservation... reservations) {
        when(storeReservationRepository.findReminderTargets(any(), any(), any())).thenReturn(List.of(reservations));
    }

    private StoreReservation reservation(Long id, LocalDateTime visitAt, LocalDateTime createdAt) {
        StoreReservation reservation = mock(StoreReservation.class);
        when(reservation.getReservationId()).thenReturn(id);
        when(reservation.getVisitAt()).thenReturn(visitAt);
        when(reservation.getCreatedAt()).thenReturn(createdAt);
        return reservation;
    }
}