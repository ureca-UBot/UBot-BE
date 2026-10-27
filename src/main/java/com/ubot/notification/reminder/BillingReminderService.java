package com.ubot.notification.reminder;

import com.ubot.billing.entity.BillingRecord;
import com.ubot.billing.repository.BillingRecordRepository;
import com.ubot.notification.enums.NotificationType;
import com.ubot.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 요금 납부 리마인더(납부 기한 임박, 연체)를 만든다.
 *
 * <p>
 * BE에는 {@code payment_status}를 {@code OVERDUE}로 바꾸는 코드가 없으므로 상태가 아니라 납부 기한과
 * 낼 금액(총 청구액 - 납부액)으로 대상을 찾는다.
 * 알림은 3개월 뒤 삭제되면서 dedupe_key도 함께 사라지므로, 같은 알림이 다시 만들어지지 않도록 모든 조회 범위에 하한을 둔다.
 *
 * <p>
 * 트랜잭션은 알림 한 건 단위({@link NotificationService})로 걸려 한 건이 실패해도 나머지는 계속 처리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingReminderService {
    /** 납부 기한이 오늘부터 며칠 이내면 임박으로 본다. */
    static final int DUE_SOON_DAYS = 3;
    /** 납부 기한이 지난 지 며칠 이내까지 연체 알림을 만든다. */
    static final int OVERDUE_LOOKBACK_DAYS = 30;

    private final BillingRecordRepository billingRecordRepository;
    private final NotificationService notificationService;

    /** @return 새로 만든 알림 수 */
    public int sendDueSoonReminders(LocalDate today) {
        return send(NotificationType.BILLING_DUE_SOON, today, today, today.plusDays(DUE_SOON_DAYS));
    }

    /** @return 새로 만든 알림 수 */
    public int sendOverdueReminders(LocalDate today) {
        return send(NotificationType.BILLING_OVERDUE, today,
                today.minusDays(OVERDUE_LOOKBACK_DAYS), today.minusDays(1));
    }

    private int send(NotificationType type, LocalDate today, LocalDate from, LocalDate to) {
        List<BillingRecord> targets = billingRecordRepository.findUnpaidByDueDateBetween(from, to);

        int created = 0;
        for (BillingRecord billing : targets) {
            try {
                if (notificationService.createBillingReminder(billing, type, today)) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.error("요금 리마인더 생성에 실패했습니다: 유형={}, billingId={}", type, billing.getBillingId(), e);
            }
        }
        return created;
    }
}