package com.ubot.notification.reminder;

import com.ubot.notification.enums.DataUsageStage;
import com.ubot.notification.service.NotificationService;
import com.ubot.usage.repository.DataUsageTarget;
import com.ubot.usage.repository.UsageRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 데이터 사용량 리마인더 (기본 제공 데이터의 80%, 100% 도달)
 * 이번 달 사용 기록만 대상으로 하고, 매시 실행됨
 * 단계마다 dedupe_key가 달라 같은 단계는 한 번만 만들어지고, 한 번에 도달한 가장 높은 단계 하나만 선택
 * 무제한 요금제와 허용량 스냅샷이 불완전한 기록은 대상이 아님 (조회 쿼리에서 제외)
 * BE에는 사용 기록을 쓰는 코드가 없고 외부에서 채워지므로, 기록이 갱신된 뒤에야 알림이 생성됨
 * 트랜잭션은 알림 한 건 단위로 걸려 한 건이 실패해도 나머지는 계속 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataUsageReminderService {
    private final UsageRecordRepository usageRecordRepository;
    private final NotificationService notificationService;

    // 새로 만든 알림의 개수 반환
    public int sendReminders(LocalDate today) {
        List<DataUsageTarget> targets = usageRecordRepository.findDataUsageTargets(
                today.withDayOfMonth(1), DataUsageStage.lowestPercent());

        int created = 0;
        for (DataUsageTarget target : targets) {
            Optional<DataUsageStage> stage = DataUsageStage.reached(target.getDataUsedMb(), target.getAllowanceMb());
            if (stage.isEmpty()) {
                continue;
            }
            try {
                if (notificationService.createDataUsageReminder(target, stage.get(), today)) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.error("데이터 사용량 리마인더 생성에 실패했습니다: usageId={}, stage={}",
                        target.getUsageId(), stage.get(), e);
            }
        }
        return created;
    }
}