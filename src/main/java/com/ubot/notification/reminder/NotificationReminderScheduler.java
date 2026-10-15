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
 * 리마인더 알림을 만드는 스케줄러. 알림 종류별 작업을 따로 실행해 하나가 실패해도 나머지는 계속 실행한다.
 * 새 리마인더는 {@link #sendReminders()}에 작업 한 줄을 추가해 붙인다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationReminderScheduler {
    private final ReservationReminderService reservationReminderService;
    private final BillingReminderService billingReminderService;

    // 서버가 여러 대여도 한 번만 실행한다. lockAtLeastFor는 시계가 조금 늦은 다른 서버가 곧바로 다시 실행하지 않게 한다.
    @Scheduled(cron = "${notification.reminder-cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = "notificationReminder", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void sendReminders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        run("방문 예약 리마인더", () -> reservationReminderService.sendReminders(now));
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