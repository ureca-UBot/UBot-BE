package com.ubot.notification.reminder;

import com.ubot.notification.service.NotificationService;
import com.ubot.reservation.entity.StoreReservation;
import com.ubot.reservation.enums.ReservationStatus;
import com.ubot.reservation.repository.StoreReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 방문 예약 리마인더를 만든다.
 *
 * <p>
 * 대상은 지금부터 내일 끝까지 방문하는 {@code RESERVED} 예약이다. 서버가 쉬었다 돌아와도 놓친 알림을 따라잡도록
 * 방문 시각이 정확히 내일인 예약만이 아니라 범위로 찾고, 중복은 알림의 dedupe_key가 막는다.
 * 방문 24시간 이내에 잡은 예약은 이미 알고 있으므로 건너뛴다.
 *
 * <p>
 * 트랜잭션은 알림 한 건 단위({@link NotificationService})로 걸려 한 건이 실패해도 나머지는 계속 처리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationReminderService {
    static final Duration MIN_NOTICE = Duration.ofHours(24);

    private final StoreReservationRepository storeReservationRepository;
    private final NotificationService notificationService;

    /** @return 새로 만든 알림 수 */
    public int sendReminders(LocalDateTime now) {
        LocalDateTime until = now.toLocalDate().plusDays(2).atStartOfDay();
        List<StoreReservation> targets = storeReservationRepository.findReminderTargets(
                ReservationStatus.RESERVED, now, until);

        int created = 0;
        for (StoreReservation reservation : targets) {
            if (isBookedWithinMinNotice(reservation)) {
                continue;
            }
            try {
                if (notificationService.createReservationReminder(reservation, now.toLocalDate())) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.error("방문 예약 리마인더 생성에 실패했습니다: reservationId={}", reservation.getReservationId(), e);
            }
        }
        return created;
    }

    private boolean isBookedWithinMinNotice(StoreReservation reservation) {
        LocalDateTime createdAt = reservation.getCreatedAt();
        return createdAt != null && createdAt.isAfter(reservation.getVisitAt().minus(MIN_NOTICE));
    }
}