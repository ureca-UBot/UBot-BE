package com.ubot.notification.reminder;

import com.ubot.contract.entity.SubscriptionContract;
import com.ubot.contract.enums.ContractStatus;
import com.ubot.contract.enums.InstallmentStatus;
import com.ubot.contract.repository.SubscriptionContractRepository;
import com.ubot.notification.enums.DateReminderStage;
import com.ubot.notification.service.NotificationService;
import com.ubot.subscription.enums.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 약정 만료·단말 할부 만료 리마인더
 * 만료까지 남은 날짜에 따라 30일·7일·1일 전(D30·D7·D1) 알림 생성, 하루 한 번 실행하는 것을 전제로 함
 * 만료 당일(0일 남음)도 D1 단계로 취급하고 문구는 "오늘"로 안내
 * 단계마다 dedupe_key가 달라 같은 단계는 한 번만 만들어지고, 한 번에 지금 단계 하나만 선택
 * 계약을 그 단계 시점보다 늦게 등록했다면 그 단계는 건너뜀
 * BE에는 계약·할부 상태를 바꾸는 코드가 없으므로 상태가 ACTIVE인 것 중 만료일로 대상을 찾음
 * 트랜잭션은 알림 한 건 단위로 걸려 한 건이 실패해도 나머지는 계속 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractReminderService {
    // 만료까지 며칠 이내면 알림 대상으로 보는지 (가장 긴 단계 기준)
    static final int WINDOW_DAYS = DateReminderStage.D30.days();

    private final SubscriptionContractRepository subscriptionContractRepository;
    private final NotificationService notificationService;

    // 새로 만든 알림의 개수 반환
    public int sendContractReminders(LocalDate today) {
        List<SubscriptionContract> targets = subscriptionContractRepository.findByExpiresAtBetween(
                today, today.plusDays(WINDOW_DAYS), ContractStatus.ACTIVE, SubscriptionStatus.ACTIVE);
        return send("약정 만료", targets, today, SubscriptionContract::getExpiresAt,
                notificationService::createContractReminder);
    }

    // 새로 만든 알림의 개수 반환
    public int sendInstallmentReminders(LocalDate today) {
        List<SubscriptionContract> targets = subscriptionContractRepository.findByInstallmentExpiresAtBetween(
                today, today.plusDays(WINDOW_DAYS), InstallmentStatus.ACTIVE, SubscriptionStatus.ACTIVE);
        return send("할부 만료", targets, today, SubscriptionContract::getInstallmentExpiresAt,
                notificationService::createInstallmentReminder);
    }

    private int send(String name, List<SubscriptionContract> targets, LocalDate today,
            Function<SubscriptionContract, LocalDate> expiresAtOf, ReminderCreator creator) {
        int created = 0;
        for (SubscriptionContract contract : targets) {
            LocalDate expiresAt = expiresAtOf.apply(contract);
            Optional<DateReminderStage> stage = DateReminderStage
                    .at(ChronoUnit.DAYS.between(today, expiresAt),
                            DateReminderStage.D1, DateReminderStage.D7, DateReminderStage.D30)
                    .filter(current -> !isRegisteredAfterStage(contract, expiresAt, current));
            if (stage.isEmpty()) {
                continue;
            }
            try {
                if (creator.create(contract, stage.get(), today)) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.error("{} 리마인더 생성에 실패했습니다: contractId={}, stage={}",
                        name, contract.getContractId(), stage.get(), e);
            }
        }
        return created;
    }

    // 계약을 그 단계 시점(만료일 - 단계 일수의 0시)보다 늦게 등록했는지 판단
    private boolean isRegisteredAfterStage(SubscriptionContract contract, LocalDate expiresAt,
            DateReminderStage stage) {
        LocalDateTime createdAt = contract.getCreatedAt();
        return createdAt != null && createdAt.isAfter(stage.startsAt(expiresAt));
    }

    @FunctionalInterface
    private interface ReminderCreator {
        boolean create(SubscriptionContract contract, DateReminderStage stage, LocalDate today);
    }
}