package com.ubot.notification.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ubot.notification.service.NotificationService;
import com.ubot.reservation.entity.StoreReservation;
import com.ubot.reservation.enums.ReservationStatus;
import com.ubot.reservation.repository.StoreReservationRepository;
import java.time.LocalDate;
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
    @DisplayName("지금부터 내일 끝까지 방문하는 RESERVED 예약을 조회한다")
    void searchesFromNowThroughEndOfTomorrow() {
        when(storeReservationRepository.findReminderTargets(any(), any(), any())).thenReturn(List.of());

        service.sendReminders(NOW);

        verify(storeReservationRepository).findReminderTargets(
                ReservationStatus.RESERVED, NOW, LocalDateTime.of(2026, 10, 15, 0, 0));
    }

    @Test
    @DisplayName("방문 24시간 이전에 잡은 예약에는 리마인더를 만들고 새로 만든 개수를 반환한다")
    void createsReminderForReservationBookedEarly() {
        StoreReservation reservation = reservation(1L, LocalDateTime.of(2026, 10, 14, 10, 0), NOW.minusDays(3));
        when(storeReservationRepository.findReminderTargets(any(), any(), any())).thenReturn(List.of(reservation));
        when(notificationService.createReservationReminder(reservation, NOW.toLocalDate())).thenReturn(true);

        assertThat(service.sendReminders(NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("방문 24시간 이내에 잡은 예약은 건너뛴다")
    void skipsReservationBookedWithin24Hours() {
        // 방문 12시간 전(오늘 08:00)에 잡은 예약
        StoreReservation reservation = reservation(1L, LocalDateTime.of(2026, 10, 13, 20, 0),
                LocalDateTime.of(2026, 10, 13, 8, 0));
        when(storeReservationRepository.findReminderTargets(any(), any(), any())).thenReturn(List.of(reservation));

        assertThat(service.sendReminders(NOW)).isZero();
        verify(notificationService, never()).createReservationReminder(any(), any());
    }

    @Test
    @DisplayName("방문 정확히 24시간 전에 잡은 예약에는 리마인더를 만든다")
    void createsReminderForReservationBookedExactly24HoursBefore() {
        LocalDateTime visitAt = LocalDateTime.of(2026, 10, 14, 10, 0);
        StoreReservation reservation = reservation(1L, visitAt, visitAt.minusHours(24));
        when(storeReservationRepository.findReminderTargets(any(), any(), any())).thenReturn(List.of(reservation));
        when(notificationService.createReservationReminder(reservation, NOW.toLocalDate())).thenReturn(true);

        assertThat(service.sendReminders(NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 리마인더가 있는 예약은 새로 만든 개수에 포함하지 않는다")
    void doesNotCountExistingReminder() {
        StoreReservation reservation = reservation(1L, LocalDateTime.of(2026, 10, 14, 10, 0), NOW.minusDays(3));
        when(storeReservationRepository.findReminderTargets(any(), any(), any())).thenReturn(List.of(reservation));
        when(notificationService.createReservationReminder(reservation, NOW.toLocalDate())).thenReturn(false);

        assertThat(service.sendReminders(NOW)).isZero();
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지 예약은 계속 처리한다")
    void continuesAfterFailure() {
        StoreReservation failing = reservation(1L, LocalDateTime.of(2026, 10, 14, 10, 0), NOW.minusDays(3));
        StoreReservation succeeding = reservation(2L, LocalDateTime.of(2026, 10, 14, 11, 0), NOW.minusDays(3));
        when(storeReservationRepository.findReminderTargets(any(), any(), any()))
                .thenReturn(List.of(failing, succeeding));
        doThrow(new IllegalStateException("db error"))
                .when(notificationService).createReservationReminder(eq(failing), any(LocalDate.class));
        when(notificationService.createReservationReminder(eq(succeeding), any(LocalDate.class))).thenReturn(true);

        assertThat(service.sendReminders(NOW)).isEqualTo(1);
    }

    private StoreReservation reservation(Long id, LocalDateTime visitAt, LocalDateTime createdAt) {
        StoreReservation reservation = mock(StoreReservation.class);
        when(reservation.getReservationId()).thenReturn(id);
        when(reservation.getVisitAt()).thenReturn(visitAt);
        when(reservation.getCreatedAt()).thenReturn(createdAt);
        return reservation;
    }
}