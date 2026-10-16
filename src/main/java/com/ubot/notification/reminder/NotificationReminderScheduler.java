package com.ubot.notification.reminder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;

/**
 * 리마인더 알림을 만드는 스케줄러
 * 방문 예약은 매시, 요금은 하루 한 번 실행
 * 서버가 여러 대여도 한 번만 실행되도록 ShedLock 사용 (auto-scaling 대비)
 * lockAtLeastFor는 시계가 조금 늦은 다른 서버가 곧바로 다시 실행하지 않게 함 (auto-scaling 대비)
 */

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationReminderScheduler {
    private final ReservationReminderService reservationReminderService;
    private final BillingReminderService billingReminderService;

    @Scheduled(cron = "${notification.reservation-reminder-cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = "reservationReminder", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void sendReservationReminders() {
        LocalDateTime now = LocalDateTime.now();
        run("방문 예약 리마인더", () -> reservationReminderService.sendReminders(now));
    }

    @Scheduled(cron = "${notification.billing-reminder-cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = "billingReminder", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void sendBillingReminders() {
        LocalDate today = LocalDate.now();
        run("납부 기한 임박", () -> billingReminderService.sendDueSoonReminders(today));
        run("연체", () -> billingReminderService.sendOverdueReminders(today));
    }

    private void run(String name, IntSupplier job) {
        long startedAt = System.nanoTime();
        try {
            int created = job.getAsInt();
            log.info("{} 알림 생성을 완료했습니다: 생성={}건, 처리시간={}ms", name, created, elapsedMillis(startedAt));
        } catch (RuntimeException e) {
            log.error("{} 알림 생성에 실패했습니다: 처리시간={}ms", name, elapsedMillis(startedAt), e);
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}