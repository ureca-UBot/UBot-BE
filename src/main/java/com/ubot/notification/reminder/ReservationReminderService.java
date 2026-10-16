package com.ubot.notification.reminder;

import com.ubot.notification.enums.ReservationReminderStage;
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
import java.util.Optional;

/**
 * 방문 예약 리마인더
 * 방문까지 남은 시간에 따라 24시간·6시간·1시간 전 알림 생성
 * 단계마다 dedupe_key가 달라 같은 단계는 한 번만 만들어지고, 서버가 쉬었다 돌아와도 지금 단계의 알림만 전송
 * 예약을 그 단계 시점보다 늦게 잡았다면 그 단계는 건너뜀
 * 트랜잭션은 알림 한 건 단위로 걸려 한 건이 실패해도 나머지는 계속 처리
 */

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationReminderService {
    private final StoreReservationRepository storeReservationRepository;
    private final NotificationService notificationService;

    /** @return 새로 만든 알림 수 */
    public int sendReminders(LocalDateTime now) {
        LocalDateTime until = now.plus(ReservationReminderStage.D1.lead());
        List<StoreReservation> targets = storeReservationRepository.findReminderTargets(
                ReservationStatus.RESERVED, now, until);

        int created = 0;
        for (StoreReservation reservation : targets) {
            Optional<ReservationReminderStage> stage = ReservationReminderStage
                    .at(Duration.between(now, reservation.getVisitAt()))
                    .filter(current -> !isBookedAfterStage(reservation, current));
            if (stage.isEmpty()) {
                continue;
            }
            try {
                if (notificationService.createReservationReminder(reservation, stage.get(), now)) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.error("방문 예약 리마인더 생성에 실패했습니다: reservationId={}, stage={}",
                        reservation.getReservationId(), stage.get(), e);
            }
        }
        return created;
    }

    // 예약을 그 단계 시점(방문 시각 - 단계 시간)보다 늦게 잡았는지 판단
    private boolean isBookedAfterStage(StoreReservation reservation, ReservationReminderStage stage) {
        LocalDateTime createdAt = reservation.getCreatedAt();
        return createdAt != null && createdAt.isAfter(reservation.getVisitAt().minus(stage.lead()));
    }
}