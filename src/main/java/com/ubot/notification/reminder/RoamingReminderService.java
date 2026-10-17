package com.ubot.notification.reminder;

import com.ubot.notification.enums.DateReminderStage;
import com.ubot.notification.service.NotificationService;
import com.ubot.subscription.entity.SubscriptionRoaming;
import com.ubot.subscription.enums.SubscriptionRoamingStatus;
import com.ubot.subscription.enums.SubscriptionStatus;
import com.ubot.subscription.repository.SubscriptionRoamingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 로밍 시작·종료 리마인더
 * 시작·종료 하루 전(D1)과 당일(D0) 두 단계로 알림 생성, 하루 한 번 실행하는 것을 전제로 함
 * 단계마다 dedupe_key가 달라 같은 단계는 한 번만 만들어짐
 * BE에는 로밍 상태를 바꾸는 코드가 없으므로 상태가 아니라 시작일·종료일로 대상을 찾음
 * 시작 알림은 시작 24시간 전보다 늦게 등록한 로밍은 건너뜀 (방금 등록한 사용자에게는 보내지 않음)
 * 종료 알림은 시작일과 종료일이 같은 1일 로밍은 건너뜀
 * 트랜잭션은 알림 한 건 단위로 걸려 한 건이 실패해도 나머지는 계속 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoamingReminderService {
    static final Duration MIN_NOTICE = Duration.ofHours(24);
    private static final List<SubscriptionRoamingStatus> TARGET_STATUSES = List.of(
            SubscriptionRoamingStatus.SCHEDULED, SubscriptionRoamingStatus.ACTIVE);

    private final SubscriptionRoamingRepository subscriptionRoamingRepository;
    private final NotificationService notificationService;

    /** @return 새로 만든 알림 수 */
    public int sendStartReminders(LocalDate today) {
        List<SubscriptionRoaming> targets = subscriptionRoamingRepository.findByStartDateBetween(
                today, today.plusDays(1), TARGET_STATUSES, SubscriptionStatus.ACTIVE);

        int created = 0;
        for (SubscriptionRoaming roaming : targets) {
            Optional<DateReminderStage> stage = stageOf(today, roaming.getStartDate());
            if (stage.isEmpty() || isRegisteredWithinMinNotice(roaming)) {
                continue;
            }
            try {
                if (notificationService.createRoamingStartReminder(roaming, stage.get(), today)) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.error("로밍 시작 리마인더 생성에 실패했습니다: subscriptionRoamingId={}, stage={}",
                        roaming.getSubscriptionRoamingId(), stage.get(), e);
            }
        }
        return created;
    }

    /** @return 새로 만든 알림 수 */
    public int sendEndReminders(LocalDate today) {
        List<SubscriptionRoaming> targets = subscriptionRoamingRepository.findByEndDateBetween(
                today, today.plusDays(1), TARGET_STATUSES, SubscriptionStatus.ACTIVE);

        int created = 0;
        for (SubscriptionRoaming roaming : targets) {
            Optional<DateReminderStage> stage = stageOf(today, roaming.getEndDate());
            if (stage.isEmpty() || roaming.getStartDate().equals(roaming.getEndDate())) {
                continue;
            }
            try {
                if (notificationService.createRoamingEndReminder(roaming, stage.get(), today)) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.error("로밍 종료 리마인더 생성에 실패했습니다: subscriptionRoamingId={}, stage={}",
                        roaming.getSubscriptionRoamingId(), stage.get(), e);
            }
        }
        return created;
    }

    // 기준 날짜까지 남은 일수에 맞는 단계 (당일은 D0, 하루 전은 D1)
    private Optional<DateReminderStage> stageOf(LocalDate today, LocalDate targetDate) {
        return DateReminderStage.at(ChronoUnit.DAYS.between(today, targetDate),
                DateReminderStage.D0, DateReminderStage.D1);
    }

    // 로밍을 시작 24시간 전(시작일 0시 - 24시간)보다 늦게 등록했는지 판단
    private boolean isRegisteredWithinMinNotice(SubscriptionRoaming roaming) {
        LocalDateTime createdAt = roaming.getCreatedAt();
        return createdAt != null && createdAt.isAfter(roaming.getStartDate().atStartOfDay().minus(MIN_NOTICE));
    }
}